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
import io.yupiik.ia.agent.core.configuration.ProviderCompactionConfiguration;
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.context.SpeculativeCompaction.Candidate;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.RetryMode;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.LlmHttpFailureException;
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
    private final BackoffSleeper backoffSleeper;
    private volatile boolean warnedCompactModel;

    public CompactionCoordinator(
            final CodingAgentConfiguration configuration,
            final ChatClient chatClient,
            final UsageTracker usageTracker,
            final ProviderManager providerManager,
            final Emitter emitter,
            final String sessionId,
            final BackoffSleeper backoffSleeper) {
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.usageTracker = usageTracker;
        this.providerManager = providerManager;
        this.emitter = emitter;
        this.sessionId = sessionId;
        this.backoffSleeper = backoffSleeper;
    }

    protected CompactionCoordinator() {
        this.configuration = null;
        this.chatClient = null;
        this.usageTracker = null;
        this.providerManager = null;
        this.emitter = null;
        this.sessionId = null;
        this.backoffSleeper = new BackoffSleeper();
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

    /** Captures immutable source and resolved summarizer inputs on the foreground thread. */
    public java.util.Optional<SpeculativeCompaction.Prepared> prepareSpeculation(
            final List<Message> messages, final ChatSettings settings, final int extraToolTokens) {
        final var cutoff = eligibleCutoff(messages);
        final var from = leadingSystemMessages(messages).size();
        if (cutoff <= from) {
            return java.util.Optional.empty();
        }
        try {
            final var prefix = SpeculativeCompaction.snapshot(messages.subList(0, cutoff));
            final var routed = settings == null ? ChatSettings.EMPTY : settings;
            final var model = compactModelReference(routed);
            final var template = summaryRequest(model, configSystemPrompt(), "", routed);
            final var chunkCap = effectiveChunkCap(model);
            return java.util.Optional.of(new SpeculativeCompaction.Prepared(
                    from,
                    prefix,
                    previousSummary(prefix),
                    routed,
                    template,
                    chunkCap,
                    effectiveDigestCap(model),
                    Math.max(1, summarizerMinChunkChars()),
                    usageTracker == null ? 0 : usageTracker.estimateContext(messages, extraToolTokens)));
        } catch (final IllegalArgumentException e) {
            LOGGER.fine("Skipping speculative compaction: unsupported snapshot payload");
            return java.util.Optional.empty();
        }
    }

    /** Re-evaluates normal retention, moving backwards rather than splitting a tool round. */
    public int eligibleCutoff(final List<Message> messages) {
        final var from = leadingSystemMessages(messages).size();
        var boundary = splitBoundary(
                messages, from, Math.max(2, configuration.pruning().keepRecent()), false);
        boundary = Math.min(boundary, messages.size());
        while (boundary > from && !safeBoundary(messages, boundary)) {
            boundary--;
        }
        return boundary;
    }

    private static boolean safeBoundary(final List<Message> messages, final int cutoff) {
        if (cutoff < messages.size() && "tool".equals(messages.get(cutoff).role())) {
            return false;
        }
        final var outstanding = new java.util.HashSet<String>();
        for (var i = 0; i < cutoff; i++) {
            final var message = messages.get(i);
            if (message.toolCalls() != null) {
                for (final var call : message.toolCalls()) {
                    if (call == null || call.id() == null || !outstanding.add(call.id())) {
                        return false;
                    }
                }
            }
            if ("tool".equals(message.role()) && !outstanding.remove(message.toolCallId())) {
                return false;
            }
        }
        return outstanding.isEmpty();
    }

    /** Runs the blockable hook only on the foreground thread. */
    public boolean hookVetoed() {
        if (emitter == null) {
            return false;
        }
        final var hook = new io.yupiik.ia.agent.core.events.HookResult();
        emitter.emit(new io.yupiik.ia.agent.core.events.CompactionStartingEvent(
                new io.yupiik.ia.agent.core.events.EventContext(
                        sessionId, null, null, configuration.resolveProjectDir().toString(), null, null, null, null),
                hook));
        return hook.forbidden();
    }

    /** Pure computation with auxiliary accounting, but no foreground events or truncation fallback. */
    public SpeculativeCompaction.Candidate generateSpeculativeSummary(
            final SpeculativeCompaction.Prepared source, final BooleanSupplier cancelled) {
        final var started = System.nanoTime();
        var chunkCap = source.chunkCap();
        var calls = 0;
        while (!cancelled.getAsBoolean()) {
            final var chunks =
                    chunkCompact(source.prefix().subList(source.leadingSystemCount(), source.cutoff()), chunkCap);
            final var outcome = summarizeReducing(
                    "speculative compaction",
                    configSystemPrompt(),
                    chunks,
                    source.priorSummary(),
                    null,
                    chunkCap,
                    cancelled,
                    source.settings(),
                    true,
                    source.requestTemplate(),
                    source.digestCap());
            calls += outcome.calls();
            if (cancelled.getAsBoolean()) {
                return null;
            }
            if (!outcome.summary().isBlank()) {
                return new Candidate(
                        source,
                        outcome.summary(),
                        calls,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            if (!outcome.sizeError() || chunkCap <= source.minimumChunkCap()) {
                return null;
            }
            chunkCap = Math.max(source.minimumChunkCap(), chunkCap / 2);
        }
        return null;
    }

    /** Prefix and routing validation before waiting and again immediately before installation. */
    public boolean compatibleSpeculation(
            final SpeculativeCompaction.Prepared source, final List<Message> current, final ChatSettings settings) {
        if (!source.settings().equals(settings == null ? ChatSettings.EMPTY : settings)
                || current.size() < source.cutoff()
                || eligibleCutoff(current) < source.cutoff()
                || !safeBoundary(current, source.cutoff())) {
            return false;
        }
        try {
            return source.prefix().equals(SpeculativeCompaction.snapshot(current.subList(0, source.cutoff())));
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /** Assembles against the current tail; oversized candidates are rejected, never destructively fitted. */
    public SpeculativeCompaction.Adoption validateAndAssemble(
            final SpeculativeCompaction.Candidate candidate,
            final List<Message> current,
            final int currentEligibleCutoff,
            final int extraToolTokens) {
        final var source = candidate.source();
        SpeculativeCompaction.Rejection rejection = null;
        if (currentEligibleCutoff < source.cutoff()) {
            rejection = SpeculativeCompaction.Rejection.RETENTION_SHRUNK;
        } else if (!compatibleSpeculation(source, current, source.settings())) {
            rejection = SpeculativeCompaction.Rejection.SOURCE_CHANGED;
        } else if (candidate.summary() == null || candidate.summary().isBlank()) {
            rejection = SpeculativeCompaction.Rejection.EMPTY_SUMMARY;
        }
        if (rejection != null) {
            return new SpeculativeCompaction.Adoption(false, null, rejection);
        }
        final var out = assembleSummary(current, source.leadingSystemCount(), source.cutoff(), candidate.summary());
        if (usageTracker == null
                || (long) usageTracker.estimateContext(out) + extraToolTokens > usageTracker.usableWindow()) {
            return new SpeculativeCompaction.Adoption(false, null, SpeculativeCompaction.Rejection.TOO_LARGE);
        }
        if (usageTracker.estimateContext(out) >= usageTracker.estimateContext(current)) {
            return new SpeculativeCompaction.Adoption(false, null, SpeculativeCompaction.Rejection.NO_REDUCTION);
        }
        return new SpeculativeCompaction.Adoption(true, new CompactionResult(List.copyOf(out), Mode.SUMMARY, ""), null);
    }

    private static List<Message> assembleSummary(
            final List<Message> messages, final int from, final int boundary, final String summary) {
        final var out = new ArrayList<Message>();
        out.addAll(messages.subList(0, from));
        // digest-fold: when the summarizable region's only user content is a previous
        // <compacted-context> digest (a digest-only dead-end: the digest then a long tool-churn
        // turn), fold the new summary into the previous one and REPLACE the old digest with ONE
        // digest instead of stacking a second one. The tool-round material is summarized and the
        // region dropped, so repeated /compact force actually reduces the tool-churn middle.
        // With the region dropped the new digest sits exactly at `from`, so the digestIndex
        // protection of ensureUnderWindow stays aligned with the real digest position.
        final var digestIndex = digestMessageIndex(messages, from, boundary);
        final var previous = previousSummary(messages);
        final var body = new StringBuilder();
        if (digestIndex >= 0 && previous != null && !previous.isBlank()) {
            body.append("Previous context:\n").append(previous).append("\n\n");
        }
        body.append(summary);
        out.add(Message.user(COMPACTED_MARKER + "\n" + body + "\n</compacted-context>\n\n" + DIGEST_GUIDANCE));
        out.addAll(subagentBlocks(messages.subList(from, boundary)));
        out.addAll(messages.subList(boundary, messages.size()));
        final var lastUser = lastUserMessageIndex(messages);
        if (lastUser >= 0 && lastUser < boundary && !out.contains(messages.get(lastUser))) {
            out.add(Message.user(str(messages.get(lastUser).content())));
        }
        return out;
    }

    /**
     * Index of a previous {@code <compacted-context>} digest message within {@code [from, boundary)}.
     * When the region's only real user content is that digest (no other user turn), returning it lets
     * {@link #assembleSummary} fold the new summary into the previous one instead of stacking digests.
     * Returns -1 when there is no digest, or when a real (non-digest) user message exists in the region.
     */
    private static int digestMessageIndex(final List<Message> messages, final int from, final int boundary) {
        var digest = -1;
        for (var i = from; i < boundary; i++) {
            final var m = messages.get(i);
            if (!"user".equals(m.role()) || !(m.content() instanceof String s)) {
                continue;
            }
            if (s.startsWith(COMPACTED_MARKER)) {
                if (digest < 0) {
                    digest = i;
                }
                continue;
            }
            if (s.startsWith("<pruned-context>") || s.startsWith("Your previous reply")) {
                continue; // structural markers, not a real user turn
            }
            return -1; // a real user message in the region: normal stacking path
        }
        return digest;
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
        return compact(messages, force, focus, keepLastOverride, false, cancelled, settings);
    }

    /**
     * Compacts a context, with an {@code urgent} flag: a manual, user-requested compaction that
     * trades the non-destructive contract for guaranteed progress. When the summarizer fails
     * transiently (after all retries and the optional main-model fallback), a normal compaction
     * skips the pass and keeps the raw context; an urgent one falls back to the destructive local
     * truncation instead, so a user explicitly asking to reclaim context near the window edge is
     * never left stuck above the budget by a flaky provider.
     */
    public CompactionResult compact(
            final List<Message> messages,
            final boolean force,
            final String focus,
            final int keepLastOverride,
            final boolean urgent,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        Objects.requireNonNull(cancelled, "cancelled");
        if (hookVetoed()) {
            return new CompactionResult(messages, Mode.NONE, "compaction vetoed by hook");
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
        var chunkCap = effectiveChunkCap(compactModelReference(settings));
        var halvings = 0;
        var lastSizeError = (String) null;
        // user-visible lifecycle feedback: an INFO notice right before the first summarizer call, so
        // the UI can pin a "compacting..." indicator while the (potentially slow) LLM pass runs
        if (usageTracker != null) {
            emit(AgentEvent.Notice.compactionStart(
                    "Compaction in progress... (\u2248" + usageTracker.estimateContext(messages) + " estimated tokens)",
                    sessionId));
        }
        Outcome outcome;
        while (true) {
            final var chunks = chunkCompact(messages.subList(from, boundary), chunkCap);
            outcome = summarizeReducing(
                    "compaction", configSystemPrompt(), chunks, prior, focus, chunkCap, cancelled, settings, false);
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
            List<Message> out = assembleSummary(messages, from, boundary, outcome.summary());
            final var digestIndex = leadingSystem.size();
            // an automatic compaction must land BELOW the trigger (real headroom), not just under the
            // hard usable window; urgent/forced compactions only need to fit the window
            out = ensureUnderWindow(
                    out, urgent || force ? usableWindowTokens() : compactionTriggerTokens(), force, digestIndex);
            if (halvings == 0
                    && configuration.compaction().summarizerChunkChars() == 0
                    && compactName(configuration.model().compact()) == null) {
                emit(AgentEvent.Notice.compacted(
                        "Compacted the context in " + (outcome.calls() >= 1 ? outcome.calls() : 1)
                                + " summarizer call(s) using a window-derived chunk size.",
                        sessionId));
            }
            // success feedback: the percentage reclaimed, or the token delta when the gain is below
            // one point (percentages would round to a no-op "0% -> 0%" readout)
            if (usageTracker != null) {
                final var afterEstimate = usageTracker.estimateContext(out);
                final var beforePercent = usageTracker.contextPercent(usageTracker.estimateContext(messages));
                final var afterPercent = usageTracker.contextPercent(afterEstimate);
                final var deltaTokens = usageTracker.estimateContext(messages) - afterEstimate;
                final String compactedMessage;
                if (deltaTokens > 0 && Math.abs(beforePercent - afterPercent) < 1.0) {
                    compactedMessage = "Compacted: reclaimed ~" + deltaTokens + " tokens";
                } else {
                    compactedMessage = "Compacted: context " + Math.round(beforePercent) + "% -> "
                            + Math.round(afterPercent) + "%";
                }
                emit(AgentEvent.Notice.compacted(compactedMessage, sessionId));
            }
            return new CompactionResult(out, Mode.SUMMARY, "");
        }
        // a transient summarizer failure (5xx/network after all retries and the optional main-model
        // fallback) must not destructively truncate a heavy context: skip the pass and keep the raw
        // messages so the next automatic compaction can retry from a clean state. An urgent
        // user-requested compaction opts out of this contract and falls through to the destructive
        // truncation below so it always reclaims context.
        if (outcome.transientFailure() && !outcome.sizeError() && !urgent) {
            final var failureMessage =
                    outcome.error() == null || outcome.error().isBlank() ? "Summary failed" : outcome.error();
            LOGGER.log(SessionAwareLogRecord.of(
                    LOGGER,
                    sessionId,
                    Level.WARNING,
                    failureMessage + "; compaction skipped, context kept raw (will retry next pass)"));
            emit(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN,
                    failureMessage + "; compaction skipped, context kept raw (will retry next pass). Run /compact "
                            + "to retry now, or /compact urgent to force local truncation.",
                    sessionId));
            return new CompactionResult(messages, Mode.NONE, failureMessage + " (compaction skipped)");
        }
        final var truncated = localTruncate(messages, from, boundary);
        final var keptTruncated = ensureUnderWindow(
                truncated, urgent || force ? usableWindowTokens() : compactionTriggerTokens(), force, -1);
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
                (failureMessage.isBlank() ? "Summary failed; context truncated locally." : failureMessage)
                        + " Run /compact to retry the summarizer, or /compact urgent to force truncation.",
                sessionId));
        if (keptTruncated.equals(messages)) {
            return new CompactionResult(messages, Mode.NONE, failureMessage);
        }
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
            // Digest-only dead-end rescue: when the whole non-system region is a previous
            // <compacted-context> digest followed by a long tool-churn turn (no real user turn to
            // summarize against), the strict budget walk would return `from` (Mode.NONE) forever and
            // repeated /compact force can never reclaim the tool-churn middle. Cut at the first
            // safeBoundary tool-call/result pair so the fold logic in assembleSummary merges the new
            // summary into the previous digest. Only applies when a digest is actually present: any
            // other small history stays a genuine no-op (Mode.NONE untouched).
            if (digestMessageIndex(messages, from, size) >= 0) {
                return fallbackSafeBoundary(messages, from, keepFloor);
            }
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

    /**
     * Digest-only dead-end rescue: when the strict budget walk cannot move the boundary past
     * {@code from} (only a previous {@code <compacted-context>} digest then a long tool-churn turn),
     * cut at the first safeBoundary tool-call/result pair so repeated {@code /compact} (force)
     * actually reduces the tool-churn middle instead of returning {@link Mode#NONE} forever. The
     * fold logic in {@link #assembleSummary} then merges the new digest into the previous one.
     */
    private int fallbackSafeBoundary(final List<Message> messages, final int from, final int keepFloor) {
        final var size = messages.size();
        if (size <= from + 2) {
            return from; // too small to cut anything
        }
        var boundary = size - keepFloor;
        if (boundary <= from) {
            boundary = from + 1;
        }
        while (boundary < size && !safeBoundary(messages, boundary)) {
            boundary++;
        }
        if (boundary >= size || boundary <= from) {
            return from; // no safe pair in the whole list: genuinely non-compactable
        }
        // at least the pair [from, boundary) is summarizable (the digest or the tool round)
        return boundary;
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
     * Guarantees the compacted message list fits under a target budget. The kept raw messages are
     * normally preserved verbatim; when the kept tail alone is heavy or the target is small, it is
     * progressively spilled: tool outputs are capped, retried until the list is within budget but at least
     * once shrunk. The {@code digestIndex} message (the generated summary) is never spoken to, so the
     * compacted context survives the size-fitting unchanged. This closes the gap where compaction
     * "succeeded" but the output still overflowed.
     *
     * @param targetTokens the budget the returned list must not exceed. Automatic compactions pass the
     *                     compaction trigger (so one pass buys real headroom below the wall and the
     *                     once-per-turn recovery rarely needs to fire); urgent/overflow compactions pass
     *                     the usable window (the hard input limit).
     */
    private List<Message> ensureUnderWindow(
            final List<Message> out, final int targetTokens, final boolean tight, final int digestIndex) {
        if (usageTracker == null) {
            return out;
        }
        var list = out;
        final var cap = tight ? effectiveSpliceChars() / 2 : effectiveSpliceChars();
        for (var guard = 0; guard < 8; guard++) {
            if (usageTracker.estimateContext(list) <= targetTokens) {
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
            final ChatSettings settings,
            final boolean quiet) {
        return summarizeReducing(
                label,
                systemPrompt,
                chunks,
                priorSummary,
                focus,
                chunkCap,
                cancelled,
                settings,
                quiet,
                summaryRequest(compactModelReference(settings), systemPrompt, "", settings),
                effectiveDigestCap(compactModelReference(settings)));
    }

    private ChatCompletionRequest summaryRequest(
            final String model, final String systemPrompt, final String prompt, final ChatSettings settings) {
        final var routed = settings == null ? ChatSettings.EMPTY : settings;
        return new ChatCompletionRequest(
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
                .withSessionId(sessionId)
                .withSettings(routed)
                .withTuning(
                        providerManager == null ? null : providerManager.resolvedBackgroundTuning(routed, "compact"));
    }

    private Outcome summarizeReducing(
            final String label,
            final String systemPrompt,
            final List<String> chunks,
            final String priorSummary,
            final String focus,
            final int chunkCap,
            final BooleanSupplier cancelled,
            final ChatSettings settings,
            final boolean quiet,
            final ChatCompletionRequest template,
            final int digestCap) {
        final var summary = new StringBuilder();
        var calls = 0;
        if (!quiet) {
            warnOnceWhenNoCompactModel();
            emit(new AgentEvent.Status(
                    "compacting (" + label + ", chunk 1/" + chunks.size() + ")...", sessionId, null, null));
        }
        for (var c = 0; c < chunks.size(); c++) {
            if (cancelled.getAsBoolean()) {
                return new Outcome("", label + " cancelled", calls, false, false);
            }
            final var prompt = chunkPrompt(chunks, c, summary, priorSummary, focus, digestCap);
            String part = null;
            String error = null;
            for (var attempt = 0; attempt < retries(settings); attempt++) {
                try {
                    if (cancelled.getAsBoolean()) {
                        return new Outcome("", label + " cancelled", calls, false, false);
                    }
                    final var request = new ChatCompletionRequest(
                            template.model(),
                            List.of(Message.system(systemPrompt), Message.user(prompt)),
                            template.temperature(),
                            template.topP(),
                            false,
                            null,
                            template.toolChoice(),
                            template.maxTokens(),
                            template.reasoningEffort(),
                            template.others(),
                            template.sessionId(),
                            template.settings());
                    final var res = chatClient.chat(
                            request.withRetryMode(RetryMode.COOPERATIVE),
                            pause -> {
                                if (!quiet) {
                                    emit(new AgentEvent.Pause(
                                            pause.kind() == io.yupiik.ia.agent.core.llm.PauseEvent.Pause.THROTTLE
                                                    ? AgentEvent.Pause.Kind.THROTTLE
                                                    : AgentEvent.Pause.Kind.RETRY,
                                            "compacting " + label + ": " + pause.message(),
                                            pause.durationMs(),
                                            sessionId));
                                }
                            },
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
                    return new Outcome("", label + " summarizer input exceeds the context window", calls, true, false);
                } catch (final IOException e) {
                    if (isSizeCaused(e)) {
                        // a server-side "context window exceeded / max_tokens" rejection (400/413) is the
                        // HTTP mirror of ContextWindowExceededException: the chunk is too large for the
                        // model, so signal the caller to halve the chunk cap rather than burn the retry
                        // budget and skip keep-raw on a deterministic sizing problem
                        return new Outcome(
                                "", label + " summarizer input exceeds the context window", calls, true, false);
                    }
                    error = label + " summarizer failed: " + e.getMessage();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Outcome("", label + " interrupted", calls, false, false);
                }
                if (attempt + 1 < retries(settings)) {
                    if (cancelled.getAsBoolean()) {
                        return new Outcome("", label + " cancelled", calls, false, false);
                    }
                    final var delay = backoff(attempt, settings);
                    if (!quiet) {
                        emit(new AgentEvent.Pause(
                                AgentEvent.Pause.Kind.RETRY,
                                "compacting " + label + ": retrying in ~" + (delay / 1000) + "s...",
                                delay,
                                sessionId));
                    }
                    backoffSleeper.sleep(delay);
                }
            }
            if (part == null || part.isBlank()) {
                if (error == null) {
                    error = label + " produced no summary; truncated locally";
                }
                // a transient summarizer failure (5xx/network after all retries) with the main-model
                // fallback enabled: retry once on the provider's conversation model before giving up
                if (fallbackToMainModel(settings)) {
                    final var mainModel = mainModelReference(settings);
                    if (mainModel != null && !mainModel.equals(template.model())) {
                        if (!quiet) {
                            emit(new AgentEvent.Status(
                                    "compacting " + label + ": summarizer unavailable, retrying on " + mainModel
                                            + "...",
                                    sessionId,
                                    null,
                                    null));
                        }
                        try {
                            // rebuild the request for the main model instead of copying the compact
                            // model's template: summaryRequest re-resolves the model reference, applies
                            // the conversation-model tuning (a dedicated compact model's tuning would
                            // otherwise size maxTokens/topP/reasoning for the smaller model) and keeps
                            // the session identity (settings/session-id) on the fallback call
                            final var fallbackRequest = summaryRequest(mainModel, systemPrompt, prompt, settings);
                            final var res = chatClient.chat(
                                    fallbackRequest.withRetryMode(RetryMode.COOPERATIVE), null, cancelled);
                            calls++;
                            if (usageTracker != null) {
                                usageTracker.recordAuxiliary(res.usage());
                            }
                            final var fallbackPart = res.message() == null
                                    ? ""
                                    : str(res.message().content());
                            if (fallbackPart != null && !fallbackPart.isBlank()) {
                                appendBounded(summary, fallbackPart, digestCap);
                                if (!quiet && c + 1 < chunks.size()) {
                                    emit(new AgentEvent.Status(
                                            "compacting (" + label + ", chunk " + (c + 2) + "/" + chunks.size()
                                                    + ")...",
                                            sessionId,
                                            null,
                                            null));
                                }
                                continue;
                            }
                        } catch (final ContextWindowExceededException e) {
                            // the main model is not smaller than the compact model: a window error here
                            // means the chunk is genuinely too large for any model, treat as size error
                            return new Outcome(
                                    "", label + " summarizer input exceeds the context window", calls, true, false);
                        } catch (final IOException e) {
                            if (isSizeCaused(e)) {
                                // the main model has a (possibly smaller) window too: a sizing rejection
                                // here is deterministic and must halve the chunk cap, not fall through to
                                // the transient skip
                                return new Outcome(
                                        "", label + " summarizer input exceeds the context window", calls, true, false);
                            }
                            error = label + " summarizer failed also on " + mainModel + ": " + e.getMessage();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new Outcome("", label + " interrupted", calls, false, false);
                        }
                    }
                }
                return new Outcome("", error, calls, false, true);
            }
            appendBounded(summary, part, digestCap);
            if (!quiet && c + 1 < chunks.size()) {
                emit(new AgentEvent.Status(
                        "compacting (" + label + ", chunk " + (c + 2) + "/" + chunks.size() + ")...",
                        sessionId,
                        null,
                        null));
            }
        }
        return new Outcome(summary.toString(), "", calls, false, false);
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
    private record Outcome(String summary, String error, int calls, boolean sizeError, boolean transientFailure) {}

    /**
     * Whether an {@link IOException} from the summarizer call is a context-sizing rejection rather
     * than a transient infrastructure failure. Server-side window rejections surface as
     * {@link LlmHttpFailureException} with a 400/413 status and a body naming a context-length or
     * token budget ("context_length_exceeded", "maximum context length", "max_tokens", "please
     * reduce your prompt", ...). These are deterministic: the chunk is too large for the model's
     * window, so they must drive the chunk-cap halving restart just like a client-side
     * {@link ContextWindowExceededException} - not be retried and then skipped keep-raw, which is
     * what left a heavy context stuck above its budget when the compact model refused oversized
     * chunks.
     */
    private static boolean isSizeCaused(final IOException e) {
        if (!(e instanceof final LlmHttpFailureException httpFailure)) {
            return false;
        }
        final var status = httpFailure.status();
        if (status != 400 && status != 413) {
            return false;
        }
        final var body = httpFailure.body();
        if (body == null || body.isBlank()) {
            return false;
        }
        final var lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("context window")
                || lower.contains("max_tokens")
                || lower.contains("max tokens")
                || lower.contains("too large")
                || lower.contains("reduce your prompt");
    }

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

    private int retries(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        final var configured = overrides != null && overrides.retriesMaxAttempts() > 0
                // per-provider compaction.retriesMaxAttempts wins
                ? overrides.retriesMaxAttempts()
                : configuration.compaction() != null
                                && configuration.compaction().retriesMaxAttempts() > 0
                        // scoped global yca.compaction.retriesMaxAttempts (conversation keeps
                        // yca.llm.retriesMaxAttempts)
                        ? configuration.compaction().retriesMaxAttempts()
                        : (configuration.llm() == null ? 1 : configuration.llm().retriesMaxAttempts());
        return Math.max(1, configured);
    }

    /** The per-provider compaction block for the session's provider, or null when absent/unresolvable. */
    private ProviderCompactionConfiguration compactionOverrides(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        final var providerId = settings == null ? null : settings.providerId();
        final var provider = providerId == null || providerId.isBlank()
                ? providerManager.current()
                : providerManager.provider(providerId);
        return provider == null ? null : provider.compaction();
    }

    /** Per-provider trigger safety percent, or -1 when unset (inherit the global value). */
    public int resolvedTriggerSafetyPercent(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        return overrides == null ? -1 : overrides.triggerSafetyPercent();
    }

    /** Per-provider gate attempt budget, or -1 when unset (inherit the global value). */
    public int resolvedGateMaxAttempts(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        return overrides == null ? -1 : overrides.gateMaxAttempts();
    }

    /** Per-provider gate wall-clock budget (ms), or -1 when unset (inherit the global value). */
    public long resolvedGateMaxDurationMs(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        return overrides == null ? -1 : overrides.gateMaxDurationMs();
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
     *
     * @param compactModel reference of the summarizer model ({@code null} falls back to the
     *                     conversation model, the historical behavior when no background model)
     */
    int effectiveChunkCap() {
        return effectiveChunkCap(null);
    }

    int effectiveChunkCap(final String compactModel) {
        final var explicit = summarizerChunkChars();
        if (explicit > 0) {
            return explicit;
        }
        final var floor = Math.max(1, summarizerMinChunkChars());
        if (usageTracker == null) {
            return floor;
        }
        // size against the summarizer model's own usable window (a smaller background model than the
        // conversation model must not drive a guaranteed summarizer overflow), falling back to the
        // conversation model's window when no dedicated summarizer model is configured
        final var usable = usageTracker.usableWindowForModel(compactModel);
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
        return effectiveDigestCap(null);
    }

    int effectiveDigestCap(final String compactModel) {
        final var explicit = summarizerDigestChars();
        if (explicit > 0) {
            return explicit;
        }
        return Math.max(1, effectiveChunkCap(compactModel) / 2);
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
     * LLM-free local reduction toward a target: head+tail splices the pre-digest tool outputs so the
     * estimate drops toward {@code targetTokens}. Never touches the system prompt (index 0), the
     * digest, or the kept-tail semantics. Returns a {@link Mode#SHRUNK} result when something changed,
     * {@link Mode#NONE} otherwise.
     */
    public CompactionResult localSpill(final List<Message> messages, final int targetTokens) {
        if (usageTracker == null || messages == null || messages.isEmpty()) {
            return new CompactionResult(messages == null ? List.of() : messages, Mode.NONE, "");
        }
        final var out = List.copyOf(messages);
        final var digestIndex = leadingSystemMessages(messages).size();
        final var spilled = shrinkTail(out, effectiveSpliceChars(), digestIndex);
        if (spilled == out) {
            return new CompactionResult(out, Mode.NONE, "");
        }
        // if a single splice pass is not enough, keep going (bounded) toward the target
        var list = spilled;
        for (var guard = 0; guard < 8 && usageTracker.estimateContext(list) > targetTokens; guard++) {
            final var next = shrinkTail(list, effectiveSpliceChars(), digestIndex);
            if (next == list) {
                break;
            }
            list = next;
        }
        return new CompactionResult(list, Mode.SHRUNK, "local spill");
    }

    /**
     * The automatic-compaction trigger in estimated prompt tokens: the opencode-style buffer trigger
     * capped at a safety fraction of the usable window (see {@link ContextBudget#compactionTrigger(int, int, int, int, int)}).
     * Mirrors {@code ContextManager#compactionTriggerTokens()} so both surfaces agree; falls back to the
     * usable window when compaction is disabled or the window is unknown.
     */
    int compactionTriggerTokens() {
        if (usageTracker == null) {
            return Integer.MAX_VALUE;
        }
        final var c = configuration.compaction();
        if (c == null || !c.enabled()) {
            return usageTracker.usableWindow();
        }
        if (c.thresholdPercent() > 0) {
            return (int) Math.ceil(usageTracker.usableWindow() * c.thresholdPercent() / 100.0);
        }
        return ContextBudget.compactionTrigger(
                usageTracker.contextWindowTokenCount().value(),
                c.bufferPercent(),
                c.bufferMinTokens(),
                usageTracker.usableWindow(),
                c.triggerSafetyPercent());
    }

    /** The hard usable window (context minus completion headroom); never below the minimum usable window. */
    int usableWindowTokens() {
        return usageTracker == null ? Integer.MAX_VALUE : usageTracker.usableWindow();
    }

    /**
     * Exponential backoff with jitter, additionally dilated by the configured requests-per-minute
     * cap so compaction retries never hammer a rate-limited provider faster than the operator asked.
     */
    private long backoff(final int attempt, final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        final var base = overrides != null && overrides.retriesBaseDelayMs() > 0
                ? overrides.retriesBaseDelayMs()
                : (configuration.llm() != null ? configuration.llm().retriesBaseDelayMs() : 500L);
        var exp = base * (1L << Math.min(attempt, 5));
        final var rateLimit = overrides != null && overrides.rateLimitRequestsPerMinute() > 0
                ? overrides.rateLimitRequestsPerMinute()
                : (configuration.llm() != null ? configuration.llm().rateLimitRequestsPerMinute() : 0);
        if (rateLimit > 0) {
            final var perRequestMs = 60_000L / rateLimit;
            exp = Math.max(exp, perRequestMs);
        }
        return exp + ThreadLocalRandom.current().nextLong(Math.max(1, exp / 4));
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
     * Whether a transient summarizer failure should retry once on the provider's main model: the
     * global {@code yca.compaction.fallbackToMainModel} default, overridden by the per-provider
     * {@code compaction.fallbackToMainModel} when set. Never falls back for context-size errors
     * (those are handled by the chunk-halving loop) and only makes sense when a dedicated compact
     * model is configured (otherwise the summarizer already runs on the main model).
     */
    private boolean fallbackToMainModel(final ChatSettings settings) {
        final var overrides = compactionOverrides(settings);
        // the framework materializes a default-filled per-provider block even when none is configured,
        // so only an explicit TRUE opts in at the provider level; the global default governs otherwise
        if (overrides != null && Boolean.TRUE.equals(overrides.fallbackToMainModel())) {
            return true;
        }
        return compactionConfigured() && configuration.compaction().fallbackToMainModel();
    }

    /** The session's main (conversation) model reference, or null when unresolvable. */
    private String mainModelReference(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        final var routed = settings == null ? ChatSettings.EMPTY : settings;
        final var providerId = routed.providerId();
        final var provider = providerId == null || providerId.isBlank()
                ? providerManager.current()
                : providerManager.provider(providerId);
        if (provider == null || provider.model() == null || provider.model().isBlank()) {
            return null;
        }
        return "$" + (provider.id() == null || provider.id().isBlank() ? "default" : provider.id()) + "/"
                + provider.model();
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
