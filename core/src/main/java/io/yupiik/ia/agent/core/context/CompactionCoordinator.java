/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.ContextWindowExceededException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Context reduction strategies, never failing silently.
 *
 * <p>{@link #compact(List, boolean, String, int)} summarizes the older conversation with an LLM
 * using a structured template, keeping a turn-aligned, token-budgeted verbatim tail that never
 * splits an assistant tool call from its results, re-anchoring on the previous summary when one
 * exists and replaying the last user message when the tail does not cover it. The summarizer
 * input is chunked to fit the summarizer model: the chunk cap derives from the usable window (or
 * an explicit {@code yca.compaction.summarizerChunkChars}), and when a call fails because the
 * input is too large the cap is halved down to the configured floor and compaction retried. When
 * the summarizer eventually fails it degrades to a deterministic local truncation.
 *
 * <p>{@link #prune(List)} is a local, LLM-free reduction of old tool outputs (the way to reclaim
 * bulk space cheaply): recent turns and a token budget of recent tool outputs are protected, then
 * old verbose tool outputs are first spliced down to their head and tail (the shrink rung) and only
 * cleared to a short marker when the volume still clears a minimum, so the operation never churns
 * the history for a tiny gain.
 */
public class CompactionCoordinator {
    private static final Logger LOGGER = Logger.getLogger(CompactionCoordinator.class.getName());

    /** Replacement content of tool outputs cleared by {@link #prune(List)}. */
    public static final String PRUNE_CLEARED_MARKER = "[old tool result cleared]";

    /** Replacement content of failed tool outputs cleared by {@link #prune(List)}: keeps the failure visible. */
    public static final String PRUNE_CLEARED_FAILED_MARKER = "[old tool result cleared - failed]";

    /** Fragment of the elision marker inserted by {@link #splice(String, int)}; detects already spliced outputs. */
    private static final String SPLICE_MARKER_FRAGMENT = " chars elided ...]";

    private static final String COMPACTED_MARKER = "<compacted-context>";

    /**
     * Guidance attached right after the digest so the consuming model treats the compacted context
     * as historical state instead of re-running or re-verifying what is already described in it
     * (small models in particular tend to loop on the same thoughts after a compaction without it).
     * Deliberately placed after {@code </compacted-context>} so {@link #previousSummary} parsing and
     * every digest detector (all keyed off the wrapper prefix) stay unaffected.
     */
    private static final String DIGEST_GUIDANCE = """
            When a compacted context is provided, treat it as historical state.
            Do not reload, reconstruct, or repeat actions already described in it.
            Continue from the state it represents; only call tools when needed for the current task.""";

    /** Required output structure of the summarizer, kept stable so repeated compactions can merge cleanly. */
    private static final String SUMMARY_TEMPLATE = """
            Output this markdown structure exactly, keeping every section even when empty:
            ## Objective
            ## Important Details
            ## Work State
            ### Completed
            ### Active
            ### Blocked
            ## Next Move
            ## Relevant Files""";

    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;
    private final UsageTracker usageTracker;
    private final ProviderManager providerManager;
    private final Emitter emitter;
    private final String sessionId;
    private volatile boolean warnedCompactModel;

    public CompactionCoordinator(
            final CodingAgentConfiguration configuration,
            final ChatClient chatClient,
            final UsageTracker usageTracker,
            final ProviderManager providerManager,
            final Emitter emitter,
            final String sessionId) {
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.usageTracker = usageTracker;
        this.providerManager = providerManager;
        this.emitter = emitter;
        this.sessionId = sessionId;
    }

    protected CompactionCoordinator() {
        this.configuration = null;
        this.chatClient = null;
        this.usageTracker = null;
        this.providerManager = null;
        this.emitter = null;
        this.sessionId = null;
    }

    /**
     * Reduces old tool outputs locally (no LLM call): the most recent turns and a budget of the
     * most recent tool outputs are protected, then old verbose tool outputs are first spliced to
     * their head and tail ({@code yca.pruning.shrinkOutputChars}) and only cleared to a marker when
     * the prunable volume still clears {@code yca.pruning.minPruneTokens}, otherwise nothing happens.
     */
    public CompactionResult prune(final List<Message> messages) {
        final var p = configuration.pruning();
        final var leadingSystem = leadingSystemMessages(messages);
        final var from = leadingSystem.size();
        var boundary = splitBoundary(messages, from, Math.max(2, p.keepRecent()), false);
        if (boundary <= from) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        // protect the most recent whole turns: the boundary can only move back (protect more)
        final var protectTurns = Math.max(1, p.protectTurns());
        var seenTurns = 0;
        for (var i = messages.size() - 1; i >= from && seenTurns < protectTurns; i--) {
            if ("user".equals(messages.get(i).role()) && ++seenTurns == protectTurns) {
                boundary = Math.min(boundary, i);
            }
        }

        final var shrinkCap = p.shrinkOutputChars();
        final var toClear = new ArrayList<Integer>();
        final var toShrink = new ArrayList<Integer>();
        var protectedTokens = 0;
        var prunableTokens = 0;
        var shrinkableTokens = 0;
        for (var i = boundary - 1; i >= from; i--) {
            final var m = messages.get(i);
            if (!"tool".equals(m.role())) {
                continue;
            }
            final var content = str(m.content());
            if (PRUNE_CLEARED_MARKER.equals(content)) {
                continue;
            }
            final var tokens = TokenEstimator.estimate(content);
            if (protectedTokens < p.protectRecentTokens()) {
                protectedTokens += tokens;
                continue;
            }
            toClear.add(i);
            prunableTokens += tokens;
            if (shrinkCap > 0 && content.length() > shrinkCap && !content.contains(SPLICE_MARKER_FRAGMENT)) {
                toShrink.add(i);
                shrinkableTokens += Math.max(0, tokens - TokenEstimator.estimate(splice(content, shrinkCap)));
            }
        }
        if (shrinkableTokens >= p.minPruneTokens()) {
            final var out = new ArrayList<Message>(messages);
            for (final var i : toShrink) {
                final var m = messages.get(i);
                out.set(
                        i,
                        new Message(
                                m.role(),
                                splice(str(m.content()), shrinkCap),
                                m.name(),
                                m.toolCalls(),
                                m.toolCallId(),
                                m.others()));
            }
            final var shrunkCount = toShrink.size();
            final var reclaimedTokens = shrinkableTokens;
            LOGGER.fine(() -> "prune shrunk " + shrunkCount + " tool output(s) (~" + reclaimedTokens + " tokens)");
            return new CompactionResult(List.copyOf(out), Mode.SHRUNK, "");
        }
        if (prunableTokens < p.minPruneTokens()) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        final var out = new ArrayList<Message>(messages);
        for (final var i : toClear) {
            final var cleared = messages.get(i);
            // keep the failure semantics visible: a cleared error must not look like a success
            final var failed = cleared.others() != null
                    && Boolean.TRUE.equals(cleared.others().get("is_error"));
            out.set(
                    i,
                    new Message(
                            "tool",
                            failed ? PRUNE_CLEARED_FAILED_MARKER : PRUNE_CLEARED_MARKER,
                            null,
                            null,
                            cleared.toolCallId(),
                            failed ? Map.of("is_error", true) : null));
        }
        final var clearedCount = toClear.size();
        final var clearedTokens = prunableTokens;
        LOGGER.fine(() -> "prune cleared " + clearedCount + " tool output(s) (~" + clearedTokens + " tokens)");
        return new CompactionResult(List.copyOf(out), Mode.CLEARED, "");
    }

    /**
     * Compacts older messages into a structured summary, retrying then degrading to a local
     * truncation. Never returns the input unchanged as a silent no-op.
     *
     * @param force whether to force compaction even below the thresholds (tighter keep floors and a
     *              halved tail budget, as used by overflow recovery)
     * @param focus optional hint folded into the summarizer prompt (model-managed compaction)
     * @param keepLastOverride optional minimum number of recent messages to keep verbatim (0 = default)
     */
    public CompactionResult compact(
            final List<Message> messages, final boolean force, final String focus, final int keepLastOverride) {
        // no interrupt flag on this path (a synchronous /compact, a tool directive): the
        // cancellation probe is a real never-true supplier rather than null
        return compact(messages, force, focus, keepLastOverride, Cancellations.NEVER, ChatSettings.EMPTY);
    }

    /**
     * @param settings the requesting session's selection (model/provider for the summarizer); null or
     *                 empty means the process default provider and model resolve the compact model
     */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        Objects.requireNonNull(cancelled, "cancelled");
        // blockable hook gate: a forbid vetoes the compaction before any real work
        if (emitter != null) {
            final var hook = new io.yupiik.ia.agent.core.events.HookResult();
            emitter.emit(new io.yupiik.ia.agent.core.events.CompactionStartingEvent(
                    new io.yupiik.ia.agent.core.events.EventContext(
                            sessionId,
                            null,
                            null,
                            configuration.resolveProjectDir().toString(),
                            null,
                            null,
                            null,
                            null),
                    hook));
            if (hook.forbidden()) {
                return new CompactionResult(messages, Mode.NONE, "compaction vetoed by hook");
            }
        }
        final var p = configuration.pruning();
        final var keepFloor =
                Math.max(2, keepLastOverride > 0 ? keepLastOverride : force ? p.keepRecent() / 2 : p.keepRecent());
        final var leadingSystem = leadingSystemMessages(messages);
        final var from = leadingSystem.size();
        final var boundary = splitBoundary(messages, from, keepFloor, force);
        if (boundary <= from) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        if (cancelled.getAsBoolean()) {
            return new CompactionResult(messages, Mode.NONE, "cancelled");
        }
        final var prior = previousSummary(messages);
        final var compactCfg = configuration.compaction();
        var chunkCap = effectiveChunkCap();
        var halvings = 0;
        var lastSizeError = (String) null;
        Outcome outcome;
        while (true) {
            final var chunks = chunkCompact(messages.subList(from, boundary), chunkCap);
            outcome = summarizeReducing(
                    "compaction", configSystemPrompt(), chunks, prior, focus, chunkCap, cancelled, settings);
            if (!outcome.sizeError()) {
                break;
            }
            // a size-caused failure means the chunk cap is too optimistic for the effective model
            // window: halve it down to the floor and restart from the prior summary (the partial
            // digest was built at the wrong granularity and is discarded)
            final var floor = Math.max(1, compactCfg.summarizerMinChunkChars());
            lastSizeError = outcome.error();
            if (chunkCap <= floor) {
                break;
            }
            halvings++;
            chunkCap = Math.max(floor, chunkCap / 2);
            emit(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN,
                    "Compaction exceeded the summarizer context window; retrying with chunks of ~" + chunkCap
                            + " chars.",
                    sessionId));
        }
        if (!outcome.summary().isBlank()) {
            List<Message> out = new ArrayList<>();
            out.addAll(leadingSystem);
            out.add(Message.user(
                    COMPACTED_MARKER + "\n" + outcome.summary() + "\n</compacted-context>\n\n" + DIGEST_GUIDANCE));
            // high-recall anchoring: delivered <subagent> blocks in the summarized region are kept
            // verbatim right after the digest so a sub-agent's outcome is never paraphrased away.
            out.addAll(subagentBlocks(messages.subList(from, boundary)));
            out.addAll(messages.subList(boundary, messages.size()));
            final var lastUser = lastUserMessageIndex(messages);
            if (lastUser >= 0 && lastUser < boundary) {
                // the current task must survive compaction even when the tail budget did not cover it
                out.add(Message.user(str(messages.get(lastUser).content())));
            }
            final var digestIndex = leadingSystem.size();
            out = ensureUnderWindow(out, force, digestIndex);
            if (halvings == 0
                    && configuration.compaction().summarizerChunkChars() == 0
                    && compactName(configuration.model().compact()) == null) {
                emit(new AgentEvent.Notice(
                        AgentEvent.Notice.Level.INFO,
                        "Compacted the context in " + (outcome.calls() >= 1 ? outcome.calls() : 1)
                                + " summarizer call(s) using a window-derived chunk size.",
                        sessionId));
            }
            return new CompactionResult(out, Mode.SUMMARY, "");
        }
        final var truncated = localTruncate(messages, from, boundary);
        final var keptTruncated = ensureUnderWindow(truncated, force, -1);
        final var failureMessage = (outcome.error() == null || outcome.error().isBlank()
                        ? (lastSizeError != null ? lastSizeError : "Summary failed")
                        : outcome.error())
                + (halvings > 0 ? " after " + halvings + " chunk-size reduction(s)" : "");
        LOGGER.log(SessionAwareLogRecord.of(
                LOGGER,
                sessionId,
                Level.WARNING,
                failureMessage.isBlank() ? "compaction failed; truncated locally" : failureMessage));
        emit(new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                failureMessage.isBlank() ? "Summary failed; context truncated locally." : failureMessage,
                sessionId));
        return new CompactionResult(keptTruncated, Mode.TRUNCATE, failureMessage);
    }

    /**
     * First index of the verbatim tail: at least {@code keepFloor} recent messages, adjusted so the
     * tail never starts on tool results orphaned from their assistant tool call, then extended
     * backwards at whole user-turn boundaries while they fit the tail token budget (halved when
     * {@code halveBudget} is set, ie. overflow recovery).
     */
    private int splitBoundary(
            final List<Message> messages, final int from, final int keepFloor, final boolean halveBudget) {
        final var size = messages.size();
        if (size <= from + keepFloor + 2) {
            return from;
        }
        var b = size - keepFloor;
        while (b < size && "tool".equals(messages.get(b).role())) {
            b++;
        }
        if (usageTracker == null) {
            return b;
        }
        final var budget = halveBudget ? tailBudgetTokens() / 2 : tailBudgetTokens();
        var acc = 0;
        while (b > from) {
            var t = b - 1;
            while (t > from && !"user".equals(messages.get(t).role())) {
                t--;
            }
            final var turnTokens = usageTracker.estimateContext(messages.subList(t, b));
            if (acc + turnTokens > budget) {
                break;
            }
            acc += turnTokens;
            b = t;
            if (acc >= budget) {
                break;
            }
        }
        return Math.max(from, b);
    }

    private int tailBudgetTokens() {
        final var configured = configuration.compaction().tailBudgetTokens();
        if (configured > 0) {
            return configured;
        }
        final var usable = usageTracker == null ? 32_000 : usageTracker.usableWindow();
        return Math.clamp(usable / 4, 2_000, 15_000);
    }

    /**
     * Summary text of the latest {@code <compacted-context>} message in the list, to re-anchor a
     * repeated compaction on instead of blindly re-summarizing from scratch; {@code null} when none.
     */
    private static String previousSummary(final List<Message> messages) {
        for (var i = messages.size() - 1; i >= 1; i--) {
            final var content = messages.get(i).content() instanceof String s ? s : null;
            if (content == null) {
                continue;
            }
            final var start = content.indexOf(COMPACTED_MARKER);
            if (start < 0) {
                continue;
            }
            final var bodyStart = content.indexOf('\n', start);
            final var end = content.indexOf("</compacted-context>", start);
            if (bodyStart < 0 || end < 0 || end <= bodyStart) {
                continue;
            }
            return content.substring(bodyStart + 1, end).strip();
        }
        return null;
    }

    /**
     * The delivered {@code <subagent ...>...</subagent>} user messages in a region, in order. Kept
     * verbatim through compaction so a sub-agent's outcome survives the summarizer (a high-recall
     * anchor: never paraphrased away, only pruned by {@code purgeDeliveredSubagents} once old).
     */
    private static List<Message> subagentBlocks(final List<Message> region) {
        final var out = new java.util.ArrayList<Message>();
        for (final var m : region) {
            final var content = m.content() instanceof String s ? s : null;
            if (m.role() != null && "user".equals(m.role()) && content != null && content.startsWith("<subagent ")) {
                out.add(m);
            }
        }
        return out;
    }

    /** Index of the last real user message (skipping compaction digests and truncation nudges), or -1. */
    private static int lastUserMessageIndex(final List<Message> messages) {
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var m = messages.get(i);
            if (!"user".equals(m.role()) || !(m.content() instanceof String s)) {
                continue;
            }
            if (s.startsWith(COMPACTED_MARKER)
                    || s.startsWith("<pruned-context>")
                    || s.startsWith("Your previous reply")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * Guarantees the compacted message list fits under the usable provider context window (the window
     * minus the completion headroom, see {@code UsageTracker#usableWindow()}). The kept raw messages are
     * normally preserved verbatim; when the kept tail alone is heavy or the window is small, it is
     * progressively spilled: tool outputs are capped, retried until the list is within budget but at least
     * once shrunk. The {@code digestIndex} message (the generated summary) is never spoken to, so the
     * compacted context survives the size-fitting unchanged. This closes the gap where compaction
     * "succeeded" but the output still overflowed.
     */
    private List<Message> ensureUnderWindow(final List<Message> out, final boolean tight, final int digestIndex) {
        if (usageTracker == null) {
            return out;
        }
        final var window = usageTracker.usableWindow();
        var list = out;
        final var cap = tight ? effectiveSpliceChars() / 2 : effectiveSpliceChars();
        for (var guard = 0; guard < 8; guard++) {
            if (usageTracker.estimateContext(list) <= window) {
                break;
            }
            final var next = shrinkTail(list, cap, digestIndex);
            if (next == list) {
                break;
            }
            list = next;
        }
        return list;
    }

    /**
     * Centrally absorbs overflow: any message whose content exceeds {@code cap} is head+tail
     * spliced. The system prompt (index 0) and the generated digest ({@code digestIndex}, when
     * present and &gt; 0) are never touched, so the reusable context survives. Returns the same
     * list when nothing changed.
     */
    private List<Message> shrinkTail(final List<Message> list, final int cap, final int digestIndex) {
        var changed = false;
        final var out = new ArrayList<Message>();
        for (var i = 0; i < list.size(); i++) {
            final var m = list.get(i);
            if (i == 0 || i == digestIndex) {
                out.add(m);
                continue;
            }
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            if (content.length() > cap) {
                final var spliced = splice(content, cap);
                out.add(new Message(m.role(), spliced, m.name(), m.toolCalls(), m.toolCallId(), m.others()));
                changed = true;
            } else {
                out.add(m);
            }
        }
        return changed ? out : list;
    }

    /** Keeps the head and tail of a long string, eliding the middle. */
    private static String splice(final String content, final int cap) {
        if (content.length() <= cap) {
            return content;
        }
        final var half = cap / 2;
        return content.substring(0, half) + "\n[... " + (content.length() - cap) + " chars elided ...]\n"
                + content.substring(content.length() - half);
    }

    /** Renders a message for the summarizer input, capping tool outputs at the configured summarizer tool-output cap. */
    private String renderForSummary(final Message m) {
        final var cap = effectiveToolOutputChars();
        final var content = str(m.content());
        final var truncated = content.length() > cap ? content.substring(0, cap) + " [truncated]" : content;
        if ("tool".equals(m.role())) {
            final var failed =
                    m.others() != null && Boolean.TRUE.equals(m.others().get("is_error"));
            return (failed ? "[Tool result FAILED]: " : "[Tool result]: ") + truncated;
        }
        if ("assistant".equals(m.role())
                && m.toolCalls() != null
                && !m.toolCalls().isEmpty()) {
            final var sb = new StringBuilder("[Assistant]: ").append(truncated);
            for (final var tc : m.toolCalls()) {
                if (tc.function() != null) {
                    sb.append("\n[Assistant tool call]: ")
                            .append(tc.function().name())
                            .append('(')
                            .append(capArguments(tc.function().arguments()))
                            .append(')');
                }
            }
            return sb.toString();
        }
        return '[' + m.role() + "] " + truncated;
    }

    /**
     * Head+tail splice of a tool call argument blob, honoring the configured cap
     * ({@code yca.compaction.toolArgumentsCapChars}, 0 = no cap). The full arguments are never
     * serialized into the summarizer input: they dominate it (a single bash command can carry tens
     * of thousands of characters) and would multiply the rolling-summary LLM calls for no digest
     * value - the exact payload stays available in the verbatim tail for recent calls.
     */
    private String capArguments(final String arguments) {
        final var cap = configuration.compaction().toolArgumentsCapChars();
        if (cap <= 0 || arguments == null || arguments.isBlank() || arguments.length() <= cap) {
            return arguments;
        }
        return splice(arguments, cap);
    }

    /** Flattens old compact messages into chunks, each bounded by the effective chunk cap. */
    List<String> chunkCompact(final List<Message> old, final int chunkCap) {
        final var chunks = new ArrayList<String>();
        final var current = new StringBuilder();
        for (final var m : old) {
            final var line = renderForSummary(m) + '\n';
            if (line.length() > chunkCap) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(line);
            } else if (current.length() + line.length() > chunkCap) {
                chunks.add(current.toString());
                current.setLength(0);
                current.append(line);
            } else {
                current.append(line);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * Summarizes chunked history with a rolling digest, retrying each call with backoff.
     * When multiple chunks must be summarized, each chunk (including the running digest) is passed
     * separately (the digest never exceeds {@code digestCap}) so no single call ever exceeds the
     * compact model's input window. A single chunk is a single LLM call: the common large-window case.
     */
    private Outcome summarizeReducing(
            final String label,
            final String systemPrompt,
            final List<String> chunks,
            final String priorSummary,
            final String focus,
            final int chunkCap,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        final var digestCap = effectiveDigestCap();
        final var summary = new StringBuilder();
        var calls = 0;
        var sizeError = false;
        warnOnceWhenNoCompactModel();
        emit(new AgentEvent.Status(
                "compacting (" + label + ", chunk 1/" + chunks.size() + ")...", sessionId, null, null));
        final var model = compactModelReference(settings);
        for (var c = 0; c < chunks.size(); c++) {
            if (cancelled.getAsBoolean()) {
                return new Outcome("", label + " cancelled", calls, false);
            }
            final var prompt = chunkPrompt(chunks, c, summary, priorSummary, focus, digestCap);
            String part = null;
            String error = null;
            for (var attempt = 0; attempt < retries(); attempt++) {
                try {
                    // no explicit compact model: the request settings alone resolve the route
                    // (the session's effective provider/model); otherwise the resolved
                    // $provider/model reference pins the target provider
                    final var requestModel = model == null || model.isBlank() ? null : model;
                    final var routedSettings = settings == null ? ChatSettings.EMPTY : settings;
                    final var res = chatClient.chat(
                            new ChatCompletionRequest(
                                            requestModel,
                                            List.of(Message.system(systemPrompt), Message.user(prompt)),
                                            null,
                                            null,
                                            false,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null)
                                    .withSessionId(sessionId)
                                    .withSettings(routedSettings)
                                    .withTuning(
                                            providerManager == null
                                                    ? null
                                                    : providerManager.resolvedBackgroundTuning(
                                                            routedSettings, "compact")),
                            // surface provider throttles/backoffs during compaction too:
                            // transient right-slot feedback, never the leading status
                            pause -> emit(new AgentEvent.Pause(
                                    pause.kind() == io.yupiik.ia.agent.core.llm.PauseEvent.Pause.THROTTLE
                                            ? AgentEvent.Pause.Kind.THROTTLE
                                            : AgentEvent.Pause.Kind.RETRY,
                                    "compacting " + label + ": " + pause.message(),
                                    pause.durationMs(),
                                    sessionId)),
                            cancelled);
                    calls++;
                    if (usageTracker != null) {
                        usageTracker.recordAuxiliary(res.usage());
                    }
                    part = res.message() == null ? "" : str(res.message().content());
                    if (part != null && !part.isBlank()) {
                        break;
                    }
                    part = null;
                } catch (final ContextWindowExceededException e) {
                    // the chunk (+ rolling digest + template) is larger than the model's real window:
                    // signal the caller to halve the chunk cap and retry from scratch
                    return new Outcome("", label + " summarizer input exceeds the context window", calls, true);
                } catch (final IOException e) {
                    error = label + " summarizer failed: " + e.getMessage();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Outcome("", label + " interrupted", calls, false);
                }
                if (attempt + 1 < retries()) {
                    if (cancelled.getAsBoolean()) {
                        return new Outcome("", label + " cancelled", calls, false);
                    }
                    emit(new AgentEvent.Pause(
                            AgentEvent.Pause.Kind.RETRY,
                            "compacting " + label + ": retrying in ~" + (backoff(attempt) / 1000) + "s...",
                            backoff(attempt),
                            sessionId));
                    sleep(backoff(attempt));
                }
            }
            if (part == null || part.isBlank()) {
                if (error == null) {
                    error = label + " produced no summary; truncated locally";
                }
                return new Outcome("", error, calls, false);
            }
            appendBounded(summary, part, digestCap);
            if (c + 1 < chunks.size()) {
                emit(new AgentEvent.Status(
                        "compacting (" + label + ", chunk " + (c + 2) + "/" + chunks.size() + ")...",
                        sessionId,
                        null,
                        null));
            }
        }
        return new Outcome(summary.toString(), "", calls, false);
    }

    /**
     * Appends a digest part to the running summary, never letting the running summary exceed
     * the effective digest cap; oldest content is dropped first so the digest stays a
     * compact rolling record rather than growing without bound (which would itself blow the
     * compact model's input window on the merge calls for long conversations).
     */
    private static void appendBounded(final StringBuilder summary, final String part, final int maxChars) {
        if (part == null || part.length() >= maxChars) {
            summary.setLength(0);
            if (part != null) {
                summary.append(part, 0, Math.min(part.length(), maxChars));
            }
            return;
        }
        if (summary.length() + part.length() <= maxChars) {
            summary.append(part).append('\n');
            return;
        }
        // Drop from the front until the new part fits, then append.
        final var needed = part.length() + 1;
        final var keep = maxChars - needed;
        if (keep <= 0) {
            summary.setLength(0);
            summary.append(part).append('\n');
        } else {
            summary.delete(0, summary.length() - keep);
            summary.append(part).append('\n');
        }
    }

    private static String chunkPrompt(
            final List<String> chunks,
            final int index,
            final StringBuilder rollingSummary,
            final String priorSummary,
            final String focus,
            final int digestCap) {
        final var sb = new StringBuilder();
        if (rollingSummary.isEmpty() && priorSummary != null && !priorSummary.isBlank()) {
            sb.append("Below is the previous summary of this conversation, followed by more conversation.")
                    .append(" Update the previous summary with the new information: the conversation wins on")
                    .append(" conflicts, move finished Active items to Completed, and keep the same structure.\n\n")
                    .append("<prior-summary>\n")
                    .append(priorSummary)
                    .append("\n</prior-summary>\n\nConversation:\n");
        } else if (rollingSummary.isEmpty()) {
            sb.append("Create a new summary of the conversation below.");
        } else {
            final var summaryText = rollingSummary.length() > digestCap
                    ? rollingSummary.substring(rollingSummary.length() - digestCap)
                    : rollingSummary.toString();
            sb.append(
                            "Below is a summary of earlier context, followed by more conversation. Merge them into one concise, lossless summary. Preserve key decisions, file paths, commands, results, current state and next steps.\n\nEarlier context summary:\n")
                    .append("<summary>")
                    .append(summaryText)
                    .append("</summary>");
        }
        sb.append(chunks.get(index));
        if (index + 1 < chunks.size()) {
            sb.append("\n(this is part ")
                    .append(index + 1)
                    .append(" of ")
                    .append(chunks.size())
                    .append("; continue merging)");
        }
        if (focus != null && !focus.isBlank()) {
            sb.append("\n\nExtra focus requested for this summary: ").append(focus.strip());
        }
        sb.append("\n\n").append(SUMMARY_TEMPLATE);
        return sb.toString();
    }

    private static String configSystemPrompt() {
        return "You are a context summarization agent. Summarize the conversation for another coding agent so it"
                + " can continue the work seamlessly. Do not continue the conversation and never reply to it:"
                + " only output the structured summary. Preserve exact file paths, symbols, commands, error strings"
                + " and URLs. Do not mention the summary process or that context was compacted.";
    }

    /**
     * Outcome of a summarization run: the generated summary, an error/fallback note, the number of
     * LLM calls spent (for the success INFO feedback) and whether the failure was size-caused (a
     * {@link ContextWindowExceededException}), which triggers the chunk-cap halving restart.
     */
    private record Outcome(String summary, String error, int calls, boolean sizeError) {}

    /** The consecutive leading {@code system}-role entries: the web project marker (if any) and the compaction marker. */
    private static List<Message> leadingSystemMessages(final List<Message> messages) {
        var i = 0;
        while (i < messages.size() && "system".equals(messages.get(i).role())) {
            i++;
        }
        return messages.subList(0, i);
    }

    private List<Message> localTruncate(final List<Message> messages, final int from, final int to) {
        final var out = new ArrayList<Message>();
        // keep every leading structural marker (compaction marker + web project marker) verbatim
        out.addAll(leadingSystemMessages(messages));
        final var cap = configuration.pruning().toolOutputMaxChars();
        for (final var m : messages.subList(from, to)) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            out.add(new Message(
                    m.role(),
                    content.length() > cap ? splice(content, cap) : content,
                    m.name(),
                    m.toolCalls(),
                    m.toolCallId(),
                    m.others()));
        }
        out.addAll(messages.subList(to, messages.size()));
        return out;
    }

    private int retries() {
        return Math.max(1, configuration.llm() == null ? 1 : configuration.llm().retriesMaxAttempts());
    }

    /*
     * Cap resolution. Defaults live in @Property(defaultValue) (summarizerChunkChars=0 auto,
     * summarizerMinChunkChars=12000, summarizerDigestChars=0 auto, summarizerToolOutputChars=2000,
     * overflowSpliceChars=500); the null-config/test fallback mirrors those defaults without
     * duplicating them as code constants.
     */

    private boolean compactionConfigured() {
        return configuration != null && configuration.compaction() != null;
    }

    private int summarizerChunkChars() {
        return compactionConfigured() ? configuration.compaction().summarizerChunkChars() : 0;
    }

    private int summarizerMinChunkChars() {
        return compactionConfigured() ? configuration.compaction().summarizerMinChunkChars() : 12_000;
    }

    private int summarizerDigestChars() {
        return compactionConfigured() ? configuration.compaction().summarizerDigestChars() : 0;
    }

    private int summarizerToolOutputChars() {
        return compactionConfigured() ? configuration.compaction().summarizerToolOutputChars() : 2_000;
    }

    private int overflowSpliceChars() {
        return compactionConfigured() ? configuration.compaction().overflowSpliceChars() : 500;
    }

    /**
     * The effective chunk cap for a single summarizer call: an explicit
     * {@code yca.compaction.summarizerChunkChars} wins; otherwise a quarter of the compact model's
     * usable window (in chars, ~4 chars/token), floored at the configured minimum so small models
     * stay safe. No upper ceiling: the fraction itself is self-bounding (a quarter of the window can
     * never exceed the window), and a too-optimistic estimate is recovered by the halving retry.
     */
    int effectiveChunkCap() {
        final var explicit = summarizerChunkChars();
        if (explicit > 0) {
            return explicit;
        }
        final var floor = Math.max(1, summarizerMinChunkChars());
        if (usageTracker == null) {
            return floor;
        }
        final var usable = usageTracker.usableWindow();
        // ~4 chars per token: the classic code/JSON average; conservative (window is already
        // reduced by the output reserve), and a too-large cap is recovered by the halving retry
        final var derived = (usable / 4L) * 4L;
        return (int) Math.max(floor, Math.min(Integer.MAX_VALUE, derived));
    }

    /**
     * The effective rolling-digest cap: an explicit {@code yca.compaction.summarizerDigestChars}
     * wins; otherwise half the chunk cap (the merge call then carries at most ~1.5x a chunk, well
     * inside the window for any chunk that fits).
     */
    int effectiveDigestCap() {
        final var explicit = summarizerDigestChars();
        if (explicit > 0) {
            return explicit;
        }
        return Math.max(1, effectiveChunkCap() / 2);
    }

    /** The per-tool-output cap used when the history is serialized into the summarizer input. */
    int effectiveToolOutputChars() {
        final var cap = summarizerToolOutputChars();
        return cap > 0 ? cap : Integer.MAX_VALUE;
    }

    /** The head+tail cap applied to kept-tail messages when the compacted context still overflows. */
    int effectiveSpliceChars() {
        final var cap = overflowSpliceChars();
        return cap > 0 ? cap : Integer.MAX_VALUE;
    }

    /**
     * Exponential backoff with jitter, additionally dilated by the configured requests-per-minute
     * cap so compaction retries never hammer a rate-limited provider faster than the operator asked.
     */
    private long backoff(final int attempt) {
        final var base = configuration.llm() != null ? configuration.llm().retriesBaseDelayMs() : 500L;
        var exp = base * (1L << Math.min(attempt, 5));
        if (configuration.llm() != null && configuration.llm().rateLimitRequestsPerMinute() > 0) {
            final var perRequestMs = 60_000L / configuration.llm().rateLimitRequestsPerMinute();
            exp = Math.max(exp, perRequestMs);
        }
        return exp + ThreadLocalRandom.current().nextLong(Math.max(1, exp / 4));
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resolves the summarizer model reference for a compaction pass: the effective provider's
     * {@code backgroundModelOverrides.compact} (blank = its own default model, "inherit" = the
     * global chain) wins, then the global {@code yca.model.compact}, then the session selection
     * routes the call (its provider, and its model when the session overrides it). Null means
     * "no explicit model": the request settings alone resolve it.
     */
    private String compactModelReference(final ChatSettings settings) {
        if (providerManager != null && configuration != null) {
            final var resolved = providerManager.resolvedCompact(settings);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        } else if (configuration != null
                && configuration.model() != null
                && compactName(configuration.model().compact()) != null) {
            // no provider manager (test wiring): still honor the global compact reference
            return compactName(configuration.model().compact());
        }
        // no background model resolved: use the session's provider/model (kept empty when the
        // selection carries no model override: the effective model of the session applies)
        if (settings == null) {
            return null;
        }
        final var providerId = settings.providerId();
        final var model = settings.model();
        if (model == null || model.isBlank()) {
            return providerId == null ? null : "$" + providerId + "/";
        }
        return (providerId == null ? "" : "$" + providerId + "/") + model;
    }

    /**
     * Surfaces, once per session, that no dedicated compact model is configured: compaction
     * summarizer calls then run on the (usually flagship) conversation model, which works but
     * costs more than a cheap summarizer would. A log hint is enough - a wrong hard-coded
     * default would break compaction entirely, so the fallback stays the current model.
     */
    private void warnOnceWhenNoCompactModel() {
        if (warnedCompactModel || configuration == null) {
            return;
        }
        warnedCompactModel = true;
        final var compact = configuration.model() == null
                ? null
                : compactName(configuration.model().compact());
        if (compact == null) {
            LOGGER.info("No yca.model.compact configured: context compaction summarizes with the current "
                    + "conversation model. Set yca.model.compact to a cheaper model to reduce background spend.");
        }
    }

    private void emit(final AgentEvent event) {
        if (emitter != null) {
            emitter.emit(event);
        }
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }

    /** The model name carried by a background compact {@link ModelVariantConfiguration}, or null when unset. */
    private static String compactName(final ModelVariantConfiguration v) {
        return v == null || v.name() == null || v.name().isBlank()
                ? null
                : v.name().strip();
    }
}
