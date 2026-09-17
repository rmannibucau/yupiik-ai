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
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.CompactionResult.Mode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Makes context compaction/pruning reliable: it never fails silently. The LLM
 * summarization is attempted with bounded retries and backoff; when it
 * repeatedly fails, the coordinator falls back to a deterministic local
 * truncation so the agent always keeps working, and it always reports what
 * actually happened (summarized, truncated, or failed) so frontends can show
 * the truth instead of a misleading success.
 */
@ApplicationScoped
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
     * compaction. Kept deliberately roomier than {@code toolOutputMaxChars} (80) so the tail's tool
     * results stay meaningful; the digest (generated summary) is never spliced regardless.
     */
    private static final int TAIL_SPLICE_CAP = 500;

    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;
    private final UsageTracker usageTracker;
    private final ProviderManager providerManager;
    private final Emitter emitter;

    public CompactionCoordinator(
            final CodingAgentConfiguration configuration,
            final ChatClient chatClient,
            final UsageTracker usageTracker,
            final ProviderManager providerManager,
            final Emitter emitter) {
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.usageTracker = usageTracker;
        this.providerManager = providerManager;
        this.emitter = emitter;
    }

    protected CompactionCoordinator() {
        this.configuration = null;
        this.chatClient = null;
        this.usageTracker = null;
        this.providerManager = null;
        this.emitter = null;
    }

    /**
     * Prunes older messages. Prefers an LLM digest but falls back to a local
     * truncation when the summarizer fails; the result marks which path ran.
     */
    public CompactionResult prune(final List<Message> messages) {
        final var p = configuration.pruning();
        final var keep = Math.max(2, p.keepRecent());
        if (messages.size() <= keep + 2) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        final var system = messages.get(0).role().equals("system") ? messages.get(0) : null;
        final var from = system == null ? 0 : 1;
        final var to = Math.max(from, messages.size() - keep);
        if (to <= from) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        final var old = messages.subList(from, to);
        final var chunks = chunkPrune(old, p.toolOutputMaxChars());
        final var outcome = summarizeRolling(
                "prune",
                configSystemPrompt("prune"),
                chunks,
                configuration.model().resolvedPrune());
        if (!outcome.summary().isBlank()) {
            final var out = new ArrayList<Message>();
            if (system != null) {
                out.add(system);
            }
            out.add(Message.user("<pruned-context>\n" + outcome.summary() + "\n</pruned-context>"));
            out.addAll(messages.subList(to, messages.size()));
            return new CompactionResult(out, Mode.SUMMARY, "");
        }
        final var truncated = localTruncate(messages, from, to);
        LOGGER.warning(outcome.error());
        emit(new AgentEvent.Notice(AgentEvent.Notice.Level.WARN, outcome.error()));
        return new CompactionResult(truncated, Mode.TRUNCATE, outcome.error());
    }

    /**
     * Compacts older messages into a summary, retrying then degrading to a
     * local truncation. Never returns the input unchanged as a silent no-op.
     */
    public CompactionResult compact(final List<Message> messages, final boolean overflow) {
        final var p = configuration.pruning();
        final var keep = Math.max(2, overflow ? p.keepRecent() / 2 : p.keepRecent());
        if (messages.size() <= keep + 2) {
            return new CompactionResult(messages, Mode.NONE, "");
        }
        final var system = messages.get(0).role().equals("system") ? messages.get(0) : null;
        final var from = system == null ? 0 : 1;
        final var to = Math.max(from, messages.size() - keep);
        final var chunks = chunkCompact(messages.subList(from, to));
        final var outcome = summarizeRolling(
                "compaction",
                configSystemPrompt("compact"),
                chunks,
                configuration.model().compact());
        if (!outcome.summary().isBlank()) {
            List<Message> out = new ArrayList<>();
            if (system != null) {
                out.add(system);
            }
            out.add(Message.user("<compacted-context>\n" + outcome.summary()
                    + "\n</compacted-context>\n\nContinue from this summary."));
            out.addAll(messages.subList(to, messages.size()));
            final var digestIndex = system == null ? 0 : 1;
            out = ensureUnderWindow(out, overflow, digestIndex);
            return new CompactionResult(out, Mode.SUMMARY, "");
        }
        final var truncated = localTruncate(messages, from, to);
        final var keptTruncated = ensureUnderWindow(truncated, overflow, -1);
        LOGGER.warning(
                outcome.error() == null || outcome.error().isBlank()
                        ? "compaction failed; truncated locally"
                        : outcome.error());
        emit(new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                outcome.error() == null || outcome.error().isBlank()
                        ? "Summary failed; context truncated locally."
                        : outcome.error()));
        return new CompactionResult(keptTruncated, Mode.TRUNCATE, outcome.error());
    }

    /**
     * Guarantees the compacted message list fits under the provider context window (with a small
     * completion headroom). The last {@code keepRecent} raw messages are normally preserved
     * verbatim; when the kept tail alone is heavy or the window is small, it is progressively
     * spilled: tool outputs are capped, retried until the list is within budget but at least once
     * shrunk. The {@code digestIndex} message (the generated summary) is never spoken to, so the
     * compacted context survives the size-fitting unchanged. This closes the gap where compaction
     * "succeeded" but the output still overflowed.
     */
    private List<Message> ensureUnderWindow(final List<Message> out, final boolean overflow, final int digestIndex) {
        if (usageTracker == null) {
            return out;
        }
        final var window = usageTracker.contextWindow();
        final var headroom = Math.max(1_000, (int) (window * 0.1));
        var list = out;
        final var cap = overflow ? TAIL_SPLICE_CAP / 2 : TAIL_SPLICE_CAP;
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

    /** Flattens old compact messages into chunks, each bounded by {@link #MAX_SUMMARY_INPUT_CHARS}. */
    private List<String> chunkCompact(final List<Message> old) {
        final var chunks = new ArrayList<String>();
        final var current = new StringBuilder();
        for (final var m : old) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            final var line = '[' + m.role() + "] "
                    + (content.length() > 2000 ? content.substring(0, 2000) + " [...]" : content) + '\n';
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

    private List<String> chunkPrune(final List<Message> old, final int cap) {
        final var chunks = new ArrayList<String>();
        final var current = new StringBuilder();
        for (final var m : old) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            final var truncated = content.length() > cap ? splice(content, cap) : content;
            final var line = '[' + m.role() + "] " + truncated + '\n';
            if (current.length() + line.length() > MAX_SUMMARY_INPUT_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(line);
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
            final String label, final String systemPrompt, final List<String> chunks, final String modelOverride) {
        final var summary = new StringBuilder();
        for (var c = 0; c < chunks.size(); c++) {
            final var prompt = chunkPrompt(chunks, c, summary);
            String part = null;
            String error = null;
            for (var attempt = 0; attempt < retries(); attempt++) {
                final var model = resolveModel(modelOverride);
                try {
                    final var res = chatClient.chat(new ChatCompletionRequest(
                            model,
                            List.of(Message.system(systemPrompt), Message.user(prompt)),
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null));
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

    private static String chunkPrompt(final List<String> chunks, final int index, final StringBuilder rollingSummary) {
        final var sb = new StringBuilder();
        if (rollingSummary.isEmpty()) {
            sb.append("Summarize the conversation below.");
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
        return sb.toString();
    }

    private static String configSystemPrompt(final String kind) {
        if ("prune".equals(kind)) {
            return "Summarize this conversation excerpt into a compact digest. Keep key decisions, file paths, commands and results. Max 40 lines.";
        }
        return "Summarize this coding agent conversation: goal, decisions, files touched, commands run, current state and next steps. Max 60 lines.";
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

    private void emit(final AgentEvent event) {
        if (emitter != null) {
            emitter.emit(event);
        }
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }
}
