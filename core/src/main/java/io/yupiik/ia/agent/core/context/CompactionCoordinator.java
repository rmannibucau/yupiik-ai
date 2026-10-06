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
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Context reduction strategies, never failing silently.
 *
 * <p>{@link #compact(List, boolean, String, int)} summarizes the older conversation with an LLM
 * using a structured template, keeping a turn-aligned, token-budgeted verbatim tail that never
 * splits an assistant tool call from its results, re-anchoring on the previous summary when one
 * exists and replaying the last user message when the tail does not cover it. When the summarizer
 * fails it degrades to a deterministic local truncation.
 *
 * <p>{@link #prune(List)} is a local, LLM-free reduction of old tool outputs (the way to reclaim
 * bulk space cheaply): recent turns and a token budget of recent tool outputs are protected, then
 * old verbose tool outputs are first spliced down to their head and tail (the shrink rung) and only
 * cleared to a short marker when the volume still clears a minimum, so the operation never churns
 * the history for a tiny gain.
 */
public class CompactionCoordinator {
    private static final Logger LOGGER = Logger.getLogger(CompactionCoordinator.class.getName());

    /**
     * Upper bound (in characters) of the flattened conversation passed to the summarizer in a single call.
     * Above this the history is split into chunks and summarized incrementally (a rolling digest), so a huge
     * transcript can never blow the compact model's input window with one oversized prompt.
     */
    private static final int MAX_SUMMARY_INPUT_CHARS = 12_000;

    /**
     * Upper bound (in characters) of the accumulated rolling digest injected into a merge call.
     * Keeping this well below the per-call input cap guarantees the rolling-summary merge prompt
     * plus the current chunk always stays inside the compact model's window.
     */
    private static final int MAX_ROLLING_SUMMARY_CHARS = 6_000;

    /**
     * Cap applied to kept-tail messages when the assembled context still exceeds the window after
     * compaction. Kept deliberately roomier than the local-truncation fallback cap so the tail's tool
     * results stay meaningful; the digest (generated summary) is never spliced regardless.
     */
    private static final int TAIL_SPLICE_CAP = 500;

    /** Per-message cap of tool outputs serialized into the summarizer input. */
    private static final int TOOL_OUTPUT_CAP_CHARS = 2_000;

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
        final var system = messages.get(0).role().equals("system") ? messages.get(0) : null;
        final var from = system == null ? 0 : 1;
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
        java.util.Objects.requireNonNull(cancelled, "cancelled");
        final var p = configuration.pruning();
        final var keepFloor =
                Math.max(2, keepLastOverride > 0 ? keepLastOverride : force ? p.keepRecent() / 2 : p.keepRecent());
        final var system = messages.get(0).role().equals("system") ? messages.get(0) : null;
        final var from = system == null ? 0 : 1;
        final var boundary = splitBoundary(messages, from, keepFloor, force);
        if (boundary <= from) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        if (cancelled.getAsBoolean()) {
            return new CompactionResult(messages, Mode.NONE, "cancelled");
        }
        final var prior = previousSummary(messages);
        final var chunks = chunkCompact(messages.subList(from, boundary));
        final var outcome = summarizeRolling(
                "compaction",
                configSystemPrompt(),
                chunks,
                prior,
                focus,
                configuration.model().compact(),
                cancelled,
                settings);
        if (!outcome.summary().isBlank()) {
            List<Message> out = new ArrayList<>();
            if (system != null) {
                out.add(system);
            }
            out.add(Message.user(
                    COMPACTED_MARKER + "\n" + outcome.summary() + "\n</compacted-context>\n\n" + DIGEST_GUIDANCE));
            out.addAll(messages.subList(boundary, messages.size()));
            final var lastUser = lastUserMessageIndex(messages);
            if (lastUser >= 0 && lastUser < boundary) {
                // the current task must survive compaction even when the tail budget did not cover it
                out.add(Message.user(str(messages.get(lastUser).content())));
            }
            final var digestIndex = system == null ? 0 : 1;
            out = ensureUnderWindow(out, force, digestIndex);
            return new CompactionResult(out, Mode.SUMMARY, "");
        }
        final var truncated = localTruncate(messages, from, boundary);
        final var keptTruncated = ensureUnderWindow(truncated, force, -1);
        LOGGER.warning(
                outcome.error() == null || outcome.error().isBlank()
                        ? "compaction failed; truncated locally"
                        : outcome.error());
        emit(new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                outcome.error() == null || outcome.error().isBlank()
                        ? "Summary failed; context truncated locally."
                        : outcome.error(),
                sessionId));
        return new CompactionResult(keptTruncated, Mode.TRUNCATE, outcome.error());
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
     * Guarantees the compacted message list fits under the provider context window (with a small
     * completion headroom). The kept raw messages are normally preserved verbatim; when the kept tail
     * alone is heavy or the window is small, it is progressively spilled: tool outputs are capped,
     * retried until the list is within budget but at least once shrunk. The {@code digestIndex}
     * message (the generated summary) is never spoken to, so the compacted context survives the
     * size-fitting unchanged. This closes the gap where compaction "succeeded" but the output still
     * overflowed.
     */
    private List<Message> ensureUnderWindow(final List<Message> out, final boolean tight, final int digestIndex) {
        if (usageTracker == null) {
            return out;
        }
        final var window = usageTracker.contextWindow();
        final var headroom = Math.max(1_000, (int) (window * 0.1));
        var list = out;
        final var cap = tight ? TAIL_SPLICE_CAP / 2 : TAIL_SPLICE_CAP;
        for (var guard = 0; guard < 8; guard++) {
            if (usageTracker.estimateContext(list) <= window - headroom) {
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

    /** Renders a message for the summarizer input, capping tool outputs at {@link #TOOL_OUTPUT_CAP_CHARS}. */
    private String renderForSummary(final Message m) {
        final var content = str(m.content());
        final var truncated = content.length() > TOOL_OUTPUT_CAP_CHARS
                ? content.substring(0, TOOL_OUTPUT_CAP_CHARS) + " [truncated]"
                : content;
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

    /** Flattens old compact messages into chunks, each bounded by {@link #MAX_SUMMARY_INPUT_CHARS}. */
    private List<String> chunkCompact(final List<Message> old) {
        final var chunks = new ArrayList<String>();
        final var current = new StringBuilder();
        for (final var m : old) {
            final var line = renderForSummary(m) + '\n';
            if (line.length() > MAX_SUMMARY_INPUT_CHARS) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(line);
            } else if (current.length() + line.length() > MAX_SUMMARY_INPUT_CHARS) {
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
     * separately so no single call ever exceeds the compact model's input window.
     */
    private Outcome summarizeRolling(
            final String label,
            final String systemPrompt,
            final List<String> chunks,
            final String priorSummary,
            final String focus,
            final String modelOverride,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        final var summary = new StringBuilder();
        warnOnceWhenNoCompactModel();
        emit(new AgentEvent.Status(
                "compacting (" + label + ", chunk 1/" + chunks.size() + ")...", sessionId, null, null));
        for (var c = 0; c < chunks.size(); c++) {
            if (cancelled.getAsBoolean()) {
                return new Outcome("", label + " cancelled");
            }
            final var prompt = chunkPrompt(chunks, c, summary, priorSummary, focus);
            String part = null;
            String error = null;
            for (var attempt = 0; attempt < retries(); attempt++) {
                final var model = resolveModel(modelOverride);
                try {
                    final var res = chatClient.chat(
                            new ChatCompletionRequest(
                                            model,
                                            List.of(Message.system(systemPrompt), Message.user(prompt)),
                                            null,
                                            null,
                                            false,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null)
                                    .withSettings(settings),
                            // surface provider throttles/backoffs during compaction too
                            pause -> emit(new AgentEvent.Status(
                                    "compacting " + label + ": " + pause.message(), sessionId, null, null)),
                            cancelled);
                    if (usageTracker != null) {
                        usageTracker.recordAuxiliary(res.usage());
                    }
                    part = res.message() == null ? "" : str(res.message().content());
                    if (part != null && !part.isBlank()) {
                        break;
                    }
                    part = null;
                } catch (final IOException e) {
                    error = label + " summarizer failed: " + e.getMessage();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    error = label + " interrupted";
                    return new Outcome("", error);
                }
                if (attempt + 1 < retries()) {
                    if (cancelled.getAsBoolean()) {
                        return new Outcome("", label + " cancelled");
                    }
                    emit(new AgentEvent.Status(
                            "compacting " + label + ": retrying in ~" + (backoff(attempt) / 1000) + "s...",
                            sessionId,
                            null,
                            null));
                    sleep(backoff(attempt));
                }
            }
            if (part == null || part.isBlank()) {
                if (error == null) {
                    error = label + " produced no summary; truncated locally";
                }
                return new Outcome("", error);
            }
            appendBounded(summary, part, MAX_ROLLING_SUMMARY_CHARS);
            if (c + 1 < chunks.size()) {
                emit(new AgentEvent.Status(
                        "compacting (" + label + ", chunk " + (c + 2) + "/" + chunks.size() + ")...",
                        sessionId,
                        null,
                        null));
            }
        }
        return new Outcome(summary.toString(), "");
    }

    /**
     * Appends a digest part to the running summary, never letting the running summary exceed
     * {@link #MAX_ROLLING_SUMMARY_CHARS}; oldest content is dropped first so the digest stays a
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
            final String focus) {
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
            final var summaryText = rollingSummary.length() > MAX_ROLLING_SUMMARY_CHARS
                    ? rollingSummary.substring(rollingSummary.length() - MAX_ROLLING_SUMMARY_CHARS)
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

    private record Outcome(String summary, String error) {}

    private List<Message> localTruncate(final List<Message> messages, final int from, final int to) {
        final var out = new ArrayList<Message>();
        if (messages.get(0).role().equals("system")) {
            out.add(messages.get(0));
        }
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

    private String resolveModel(final String override) {
        return providerManager.resolveModel(override);
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
        final var compact =
                configuration.model() == null ? null : configuration.model().compact();
        if (compact == null || compact.isBlank()) {
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
}
