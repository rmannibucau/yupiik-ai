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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * UI-less conversation state shared by the shells: transcript, streaming
 * buffers, status and usage counters. Agent events are folded in here while
 * the renderers only read, which keeps this class testable without a terminal.
 * <p>
 * The transcript holds markdown; thought and tool blocks are wrapped in
 * {@code THOUGHT_BEGIN}/{@code THOUGHT_END} and {@code TOOL_BEGIN}/
 * {@code TOOL_END} markers so frontends can render (or hide) them as typed
 * regions; captured log records are wrapped in {@code LOG_BEGIN}/{@code LOG_END}
 * markers so the TUI can render them with their own styling, see
 * {@link #regions(String)}.
 */
public class ConversationTranscript {
    public static final String THOUGHT_BEGIN = "<<<yca:thought>>>";
    public static final String THOUGHT_END = "<<<yca:/thought>>>";
    public static final String TOOL_BEGIN = "<<<yca:tool>>>";
    public static final String TOOL_END = "<<<yca:/tool>>>";
    public static final String LOG_BEGIN = "<<<yca:log>>>";
    public static final String LOG_END = "<<<yca:/log>>>";

    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;
    private static final int MAX_EMBEDDED_TOOL_CHARS = 4000;
    /**
     * Trailing partial-ref fragment of a split seam tag at the very end of the flushed text:
     * the first half ("@1250", "@b7", lone "@") of a split tag the streamer cut mid-tag. Only
     * matched at a token boundary (caller checks the preceding char), so "email@x" is never
     * eaten. The fragment is re-queued into {@code pending} to complete on the next delta; the
     * mirror half ("4@") needs no rule here because it can only arrive in the same buffer that
     * already holds the re-queued first half, forming the complete "@12504@" tag that the normal
     * scrub removes.
     */
    private static final Pattern TRAILING_SEAM_FRAGMENT = Pattern.compile("@(?:[1-9]\\d*|b[1-9]\\d*)?$");

    private final StringBuilder transcript = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    /**
     * Whether the {@code **Assistant**} label was already emitted for the current
     * turn: a turn streams its answer in several chunks (one per reasoning round,
     * split by tool calls) but is labelled once, so the transcript reads as a
     * single answer interleaved with its tools and thoughts.
     */
    private boolean answerHeaderWritten;
    /**
     * Pending tool executions, one {@link RunningTool} per started-not-finished
     * call. Keyed by tool name with a FIFO per name so parallel read-only tools
     * (started/finished emitted by worker threads) each keep their own command,
     * stream and elapsed clock instead of aliasing a single running-tool slot.
     */
    private final Map<String, ArrayDeque<RunningTool>> runningTools = new HashMap<>();

    private long toolCallOrdinal;
    private volatile boolean busy;
    private volatile String status = "";
    private volatile int promptTokens;
    private volatile int completionTokens;
    private volatile long cacheReadTokens;
    private volatile int estimatedContext;
    private volatile int lastRealPromptTokens;
    private volatile double calibrationRatio;
    private volatile int contextPercent;
    // the last account quota carried by a usage event; kept as atomic scalars so readers stay cheap
    private volatile boolean quotaVisible;
    private volatile String quotaPlanType;
    private volatile Double quotaUsedPercent;
    private volatile long quotaWindowSeconds;
    private volatile long quotaResetAtEpochSeconds;
    private volatile String quotaWindowLabel;
    private volatile String quotaCreditsBalance;
    private volatile boolean quotaCreditsUnlimited;
    private volatile List<AgentEvent.UsageUpdated.Quota.Window> quotaWindows = List.of();
    private volatile long maxHistoryCharacters = DEFAULT_MAX_HISTORY_CHARACTERS;

    /**
     * Speaker labels rendered in the transcript headers (defaults match the historic
     * {@code **You**}/{@code **Assistant**} markers). Set through
     * {@link #setLabels(String, String)} (UI only: the LLM conversation keeps its
     * hardcoded {@code user}/{@code assistant} roles, these only change the rendered
     * markdown of this transcript, from the next written block on).
     */
    private volatile String userLabel = "You";

    private volatile String assistantLabel = "Assistant";

    /**
     * Sets the speaker labels used for the next blocks written to this transcript.
     * Already-rendered content keeps the labels it was written with (history is
     * never rewritten); {@link #replay} re-renders persisted sessions with the
     * current labels. Blank values restore the defaults.
     */
    public synchronized void setLabels(final String user, final String assistant) {
        this.userLabel = user == null || user.isBlank() ? "You" : user;
        this.assistantLabel = assistant == null || assistant.isBlank() ? "Assistant" : assistant;
        // markers are only embedded on the next write: no contentVersion bump (rendered
        // headers label history as it was written, never retrofitting the new name)
    }

    /** The current user speaker label (default "You"). */
    public String userLabel() {
        return userLabel;
    }

    /** The current assistant speaker label (default "Assistant"). */
    public String assistantLabel() {
        return assistantLabel;
    }

    /** The markdown marker opening a user block: {@code **<label>**}. */
    public String userMarker() {
        return "**" + userLabel + "**";
    }

    /** The markdown marker opening an assistant block: {@code **<label>**}. */
    public String assistantMarker() {
        return "**" + assistantLabel + "**";
    }

    /**
     * Optional resolver re-rendering a stored {@link Message.Identity} (provider/model/agent)
     * into speaker labels during {@link #replay}. Lets template changes and session resumes
     * rebuild each block's label from what actually produced it; when unset (or when a message
     * carries no identity) replay falls back to the current {@link #setLabels} values.
     */
    private volatile BiFunction<Message.Identity, Boolean, String> labelResolver;

    public void setLabelResolver(final BiFunction<Message.Identity, Boolean, String> resolver) {
        this.labelResolver = resolver;
    }

    private String markerFor(final Message message, final boolean user) {
        final var identity = Message.identityOf(message);
        if (identity != null && labelResolver != null) {
            final var label = labelResolver.apply(identity, user);
            if (label != null && !label.isBlank()) {
                return "**" + label + "**";
            }
        }
        return user ? userMarker() : assistantMarker();
    }

    /**
     * Monotonic counter bumped on every mutation that can change what
     * {@link #markdown(String, String)} renders (the transcript, the pending
     * answer tail, the thinking buffer or the running tools). {@code markdown()}
     * caches its built string against this version, so render loops that call it
     * once per tick with unchanged content are O(1) instead of re-concatenating
     * the whole transcript every frame (one of the busy-frame CPU hot spots).
     */
    private volatile long contentVersion;

    /**
     * Monotonic counter bumped only on durable {@link #transcript} mutations (flushes, turn/notice
     * writes, trim, reset, clear, replay, {@link #appendTranscript}) - never on the streaming
     * buffers (pending/thinking/tool stream). {@code markdown()} caches the scrubbed head against
     * this version, so a streaming turn (which bumps {@link #contentVersion} on every delta but not
     * this counter) reuses the already-scrubbed durable head instead of re-scrubbing the whole
     * transcript on every render tick - the busy-frame CPU hot spot.
     */
    private volatile long transcriptVersion;

    private long markdownVersion = -1;
    private String markdownSpinner;
    private String markdownCache;
    /** Scrubbed durable head + the {@link #transcriptVersion} it was built from, see {@link #markdown}. */
    private long scrubbedHeadVersion = -1;

    private String scrubbedHeadCache;
    /** Cached {@link MarkdownSnapshot} of the last {@link #markdownWithHead} call (see there). */
    private MarkdownSnapshot markdownHeadCache;

    /**
     * The lazily-assembled snapshot the TUI renderer keys its stable-region cache on:
     * the stable scrubbed durable head (the exact cached {@code String} instance, so a
     * streaming turn that only appends to the pending tail can detect "head unchanged"
     * by reference equality - no hashing of multi-megabyte strings) and the full
     * assembled markdown (pending tail + running-tool blocks included, exactly what
     * {@link #markdown} renders), plus the offset where the streaming tail starts.
     */
    public record MarkdownSnapshot(String head, String md, int tailStart) {}

    /** Marks the transcript-markdown inputs as changed so {@code markdown()} rebuilds its cache. */
    private synchronized void touch() {
        contentVersion++;
    }

    /** Marks the durable {@link #transcript} as changed so {@code markdown()} rebuilds its scrubbed head. */
    private synchronized void touchTranscript() {
        transcriptVersion++;
    }

    /** True while the last failure was a retryable LLM error; the status clears on the next progress event. */
    private volatile boolean retryableErrorSeen;

    /**
     * A retryable LLM failure (request failed with a transient status or a
     * rate/concurrency limit) whose retry may still succeed: such errors are
     * dropped from the status bar as soon as the turn makes progress again, so
     * a successful retry does not leave a stale red error behind. Fatal errors
     * are not detected here and stay until acknowledged.
     */
    public static boolean isRetryableLlmError(final String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        final var m = message.toLowerCase(Locale.ROOT);
        if (m.contains("concurrency_limit_exceeded")
                || m.contains("too many concurrent requests")
                || m.contains("rate limit")
                || m.contains("rate_limit")
                || m.contains("too many requests")) {
            return true;
        }
        if (!m.startsWith("llm request failed (") && !m.startsWith("llm call failed (")) {
            return false;
        }
        final var start = message.indexOf('(');
        final var end = message.indexOf(')', start + 1);
        if (start < 0 || end < 0 || end <= start + 1) {
            // request failed with an unknown status: the retry may still recover
            return true;
        }
        try {
            final var status =
                    Integer.parseInt(message.substring(start + 1, end).strip());
            return status == 408 || status == 425 || status == 429 || status >= 500;
        } catch (final NumberFormatException e) {
            return true;
        }
    }

    /** True when the event proves the turn is making progress again after a failure. */
    public static boolean isTurnProgress(final AgentEvent event) {
        return event instanceof AgentEvent.TextDelta
                || event instanceof AgentEvent.TextReset
                || event instanceof AgentEvent.ThinkingDelta
                || event instanceof AgentEvent.ToolStarted
                || event instanceof AgentEvent.ToolStream
                || event instanceof AgentEvent.ToolFinished
                || event instanceof AgentEvent.TurnDone;
    }

    /** Splits GLM-style inline {@code <think>} reasoning out of the text channel. */
    protected final ThinkTagSplitter thinkTags;

    private final ThinkTagSplitter.Parser inlineThink;

    /**
     * When true this instance folds subagent-tagged events instead of dropping
     * them: used for the per-subagent captures the TUI renders through
     * {@code /subagents <id>}. Parent transcripts keep {@code false} so a
     * subagent stream never pollutes the parent conversation (only the
     * SubagentStarted/SubagentCompleted markers are folded there).
     */
    private final boolean captureSubagentEvents;

    /**
     * Source of the seam-scrub ref whitelist for live render surfaces ({@link #markdown} /
     * {@link #thinkingText}). Defaults to a dedicated instance; callers that share a real
     * {@code ContextCompression} (e.g. the one owned by {@code ContextView}) should inject it so the
     * dangling-withhold keys off the refs actually rendered in that conversation.
     */
    private ContextCompression compression;

    public ConversationTranscript() {
        this(new ThinkTagSplitter());
    }

    /** Creates a capture-only transcript folding subagent-tagged events (see {@link #captureSubagentEvents}). */
    public ConversationTranscript(final boolean captureSubagentEvents) {
        this(new ThinkTagSplitter(), captureSubagentEvents);
    }

    protected ConversationTranscript(final ThinkTagSplitter thinkTags) {
        this(thinkTags, false);
    }

    protected ConversationTranscript(final ThinkTagSplitter thinkTags, final boolean captureSubagentEvents) {
        this(thinkTags, captureSubagentEvents, null);
    }

    /** Injects a shared {@link ContextCompression} whose live refs drive the dangling-seam withhold. */
    public ConversationTranscript(
            final ThinkTagSplitter thinkTags,
            final boolean captureSubagentEvents,
            final ContextCompression compression) {
        // defensive: hand-built instances (tests, generated subclasses) may pass null
        this.thinkTags = thinkTags != null ? thinkTags : new ThinkTagSplitter();
        this.inlineThink = this.thinkTags.create();
        this.captureSubagentEvents = captureSubagentEvents;
        this.compression = compression != null ? compression : new ContextCompression(null);
    }

    // Per-turn metrics
    private volatile int turnLlmCalls;
    private volatile long turnThinkingMs;
    private volatile long turnToolSumMs;
    private volatile long turnToolWallMs;
    private volatile int turnToolsRun;
    private volatile long turnDurationMs;
    private volatile AgentEvent.Throughput turnThroughput = AgentEvent.Throughput.EMPTY;

    /** Recent context appliances (dedup/prune/compact/...), newest first, for side feedback. */
    private final ArrayDeque<AgentEvent.Compacted> appliances = new ArrayDeque<>(8);

    /** Max number of appliance entries remembered for the sidebar. */
    private static final int MAX_APPLIANCES = 6;

    public synchronized void onEvent(final AgentEvent event) {
        // subagent streams never pollute the parent conversation: they are captured
        // per-subagent (the /subagents modal) or dropped; only the lifecycle markers
        // (SubagentStarted/SubagentCompleted) are folded into the parent transcript
        if (event.subagentId() != null
                && !captureSubagentEvents
                && !(event instanceof AgentEvent.SubagentStarted)
                && !(event instanceof AgentEvent.SubagentCompleted)) {
            return;
        }
        // a retryable LLM failure (429/concurrency limit/rate limit) leaves the status
        // strip red while the resilience layer retries internally: as soon as the turn
        // makes progress again the stale error is dropped (the retry succeeded)
        if (retryableErrorSeen && isTurnProgress(event)) {
            retryableErrorSeen = false;
            status = busy ? "working" : "";
        }
        if (event
                instanceof
                AgentEvent.TextDelta(String delta, String sessionId, String subagentId, String subagentName)) {
            // the answer's first delta usually arrives with no reset: flush the reasoning
            // so it stays in place, above the streaming answer
            flushThinking();
            // GLM-style gateways inline the reasoning in the text channel: classify the
            // stream with the think-tag state machine instead of per-chunk regex
            for (final var segment : inlineThink.feed(delta)) {
                if (segment.channel() == ThinkTagSplitter.Channel.THINKING) {
                    thinking.append(segment.text());
                } else {
                    pending.append(segment.text());
                }
            }
            touch();
        } else if (event instanceof AgentEvent.TextReset(String sessionId, String subagentId, String subagentName)) {
            pending.setLength(0);
            inlineThink.reset();
            // keep the reasoning visible: flush instead of dropping it, the model resets
            // its output right before the final answer streams
            flushThinking();
            touch();
        } else if (event
                instanceof
                AgentEvent.ThinkingDelta(String delta, String sessionId, String subagentId, String subagentName)) {
            thinking.append(delta);
            touch();
        } else if (event
                instanceof
                AgentEvent.ToolStarted(
                        String name,
                        String args,
                        long ordinal,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            flushThinking();
            // the answer streamed before the call is now final: write it in place so the
            // tool block that follows cannot jump on top of it
            flushPending();
            runningTools
                    .computeIfAbsent(name, n -> new ArrayDeque<>())
                    .addLast(new RunningTool(name, toolCommand(args), ordinal > 0 ? ordinal : 0));
            touch();
        } else if (event
                instanceof
                AgentEvent.ToolStream(
                        String name,
                        String fragment,
                        long ordinal,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            final var deque = runningTools.get(name);
            if (deque != null && !deque.isEmpty()) {
                // the most recently started tool with this name is the live one; when the stream carries
                // the runner's ordinal (#<n>) prefer the exact started call so a late fragment of an
                // earlier same-named call can never land on a sibling card
                final RunningTool tool;
                if (ordinal > 0) {
                    tool = deque.stream()
                            .filter(t -> t.ordinal() == ordinal)
                            .findFirst()
                            .orElse(deque.peekLast());
                } else {
                    tool = deque.peekLast();
                }
                tool.stream.append(fragment).append('\n');
                if (tool.stream.length() > MAX_EMBEDDED_TOOL_CHARS) {
                    tool.stream.delete(0, tool.stream.length() - MAX_EMBEDDED_TOOL_CHARS);
                }
                touch();
            }
        } else if (event
                instanceof
                AgentEvent.ToolFinished(
                        String name,
                        String output,
                        boolean success,
                        boolean pending,
                        long eventOrdinal,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            final var deque = runningTools.get(name);
            // prefer the exact started call identified by the runner's ordinal; fall back to the name
            // deque (parallel batches and legacy events without an ordinal) when no ordinal matched
            RunningTool tool = null;
            if (deque != null && eventOrdinal > 0) {
                final var it = deque.iterator();
                while (it.hasNext()) {
                    final var candidate = it.next();
                    if (candidate.ordinal() == eventOrdinal) {
                        tool = candidate;
                        it.remove();
                        break;
                    }
                }
            }
            if (tool == null && deque != null) {
                tool = deque.pollFirst();
            }
            if (deque != null && deque.isEmpty()) {
                runningTools.remove(name);
            }
            // defensive: a finish without its start (parallel batches emit from
            // worker threads) still has to close the answer chunk before the block
            flushPending();
            // one ordinal per finished call (even when nothing is rendered) so the
            // TUI's toolCallCount() mapping to cards stays exact; prefer the ordinal
            // stamped by the runner on ToolStarted so a running tool's card number is
            // stable before it finishes (open overlay + per-tool cancel rely on it)
            final var ordinal = tool != null && tool.ordinal() > 0 ? tool.ordinal() : ++toolCallOrdinal;
            if (tool != null && tool.ordinal() > toolCallOrdinal) {
                toolCallOrdinal = tool.ordinal();
            }
            final var elapsedMs = tool == null ? 0 : Math.max(0, (System.nanoTime() - tool.startedNanos) / 1_000_000);
            if (tool != null && (!tool.stream.isEmpty() || (success && output != null && !output.isBlank()))) {
                // The command is injected inside a markdown italic paragraph ("*streaming ...*").
                // A multi-line command keeps the paragraph open across softbreaks, and two bare
                // stars (eg. part_*.csv.gz and COUNT(*) in a heredoc) then pair into an emphasis
                // that hides both when a markdown renderer displays the transcript. Flatten the
                // command onto one line and escape the inline metacharacters so the header always
                // renders verbatim. Only this display copy is escaped: RunningTool.command (the
                // TUI ToolCard source) keeps the raw bytes.
                final var header = tool.command == null || tool.command.isBlank()
                        ? "*streaming `" + name + "`* (#" + ordinal + ", " + elapsedMs + " ms)"
                        : "*streaming `" + name + "`: " + escapeMarkdown(flatten(tool.command)) + "* (#" + ordinal
                                + ", " + elapsedMs + " ms)";
                final var captured = tool.stream.isEmpty()
                        ? capToolOutput(output == null ? "" : output)
                        : tool.stream.toString().stripTrailing();
                transcript
                        .append("\n\n")
                        .append(TOOL_BEGIN)
                        .append('\n')
                        .append(header)
                        .append("\n\n```text\n")
                        .append(captured)
                        .append("\n```\n")
                        .append(TOOL_END)
                        .append('\n');
            }
            if (!success && !pending) {
                transcript
                        .append("\n\n")
                        .append(TOOL_BEGIN)
                        .append("\n> tool `")
                        .append(name)
                        .append("` failed: ")
                        .append(escapeMarkdown(firstLine(output)))
                        .append(" (#")
                        .append(ordinal)
                        .append(", ")
                        .append(elapsedMs)
                        .append(" ms)\n")
                        .append(TOOL_END)
                        .append('\n');
            } else if (pending) {
                // a pending tool (e.g. check_subagents collect that timed out) is NOT a failure: render
                // a neutral "still running" marker so the transcript never reads as a red error, while
                // the tool card still exposes the pending output the model needs
                transcript
                        .append("\n\n")
                        .append(TOOL_BEGIN)
                        .append("\n> tool `")
                        .append(name)
                        .append("` still running: ")
                        .append(escapeMarkdown(firstLine(output)))
                        .append(" (#")
                        .append(ordinal)
                        .append(")\n")
                        .append(TOOL_END)
                        .append('\n');
            }
            touchTranscript();
            touch();
        } else if (event
                instanceof
                AgentEvent.UsageUpdated(
                        Usage usage,
                        int estimatedContextTokens,
                        double percent,
                        int lastRealPromptTokens,
                        double calibrationRatio,
                        String model,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        AgentEvent.UsageUpdated.Quota quota)) {
            promptTokens = usage == null ? 0 : usage.promptTokens();
            completionTokens = usage == null ? 0 : usage.completionTokens();
            cacheReadTokens = usage == null ? 0 : usage.cacheReadTokens();
            estimatedContext = estimatedContextTokens;
            this.lastRealPromptTokens = lastRealPromptTokens;
            this.calibrationRatio = calibrationRatio;
            contextPercent = (int) Math.round(percent);
            quotaVisible = quota != null && quota.visible();
            quotaPlanType = quota == null ? null : quota.planType();
            quotaUsedPercent = quota == null ? null : quota.usedPercent();
            quotaWindowSeconds = quota == null ? 0 : quota.windowSeconds();
            quotaResetAtEpochSeconds = quota == null ? 0 : quota.resetAtEpochSeconds();
            quotaWindowLabel = quota == null ? null : quota.windowLabel();
            quotaCreditsBalance = quota == null ? null : quota.creditsBalance();
            quotaCreditsUnlimited = quota != null && quota.creditsUnlimited();
            quotaWindows = quota == null ? List.of() : quota.windows();
        } else if (event
                instanceof
                AgentEvent.Compacted(
                        List<String> kinds,
                        String mode,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        AgentEvent.Compacted.CompactionTokens tokens)) {
            // side feedback only: record the appliance for the sidebar instead of touching the
            // leading status, which must stay on the short busy/ready state (a stale "context
            // dedup done..." used to hide the current spinner/error at the bottom)
            recordAppliance(new AgentEvent.Compacted(kinds, mode, sessionId, subagentId, subagentName, tokens));
            transcript
                    .append("\n\n> context ")
                    .append(String.join(", ", kinds))
                    .append(" applied (")
                    .append(mode.toLowerCase(Locale.ROOT))
                    .append(')');
            final var shrink =
                    MetricsFormat.shrink(tokens == null ? 0 : tokens.before(), tokens == null ? 0 : tokens.after());
            if (!shrink.isEmpty()) {
                transcript.append(", ").append(shrink);
            }
            transcript.append('\n');
            touchTranscript();
            touch();
        } else if (event
                instanceof
                AgentEvent.Pause(
                        AgentEvent.Pause.Kind kind,
                        String message,
                        long durationMs,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            // transient provider-imposed delay (throttle/retry backoff): side feedback only.
            // Never overwrite the leading status nor write a durable transcript line - the
            // TUI renders it in the trailing notification slot and the next pause or turn
            // progress replaces it. The failure itself is reported by the caller if the
            // retries exhaust (AgentEvent.Error), so nothing durable is lost.
            touch();
        } else if (event
                instanceof
                AgentEvent.Notice(
                        AgentEvent.Notice.Level level,
                        String message,
                        String finishReason,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        Throwable cause,
                        AgentEvent.Notice.CompactionLifecycle compaction)) {
            final var tag =
                    switch (level) {
                        case ERROR -> "⚠ error";
                        case WARN -> "⚠ warning";
                        case INFO -> "ⓘ";
                    };
            transcript.append("\n\n> ").append(tag).append(": ").append(message).append("\n");
            touchTranscript();
            touch();
            retryableErrorSeen |= isRetryableLlmError(message);
            if (level == AgentEvent.Notice.Level.ERROR) {
                status = "error: " + message;
            }
            // INFO/WARN notices are durable transcript lines only: they must not overwrite
            // the leading status slot, which stays on the short busy/ready state (the TUI
            // right notification slot is fed by the dedicated Pause/status channels).
        } else if (event
                instanceof
                AgentEvent.Status(String message, String sessionId, String subagentId, String subagentName)) {
            status = message;
        } else if (event
                instanceof
                AgentEvent.PromptIntegrated(String text, String sessionId, String subagentId, String subagentName)) {
            flushThinking();
            // the in-progress answer belongs above the prompt that just cut it off
            flushPending();
            // the queued prompt opens a **You** block that cuts the assistant answer off;
            // once the turn resumes streaming, the continued model output must open a fresh
            // **Assistant** block (markdown() re-emits the marker when this is false) instead
            // of gluing onto the **You** segment and inheriting the user rail colour
            answerHeaderWritten = false;
            thinking.setLength(0);
            inlineThink.reset();
            transcript
                    .append("\n\n---\n\n" + userMarker() + "\n\n")
                    .append(text)
                    .append('\n');
            status = "queued prompt integrated";
            touchTranscript();
            touch();
        } else if (event
                instanceof
                AgentEvent.TurnMetrics(
                        int llmCalls,
                        long thinkingMs,
                        long toolSumMs,
                        long toolWallMs,
                        int toolsRun,
                        long turnDurationMs,
                        AgentEvent.Throughput throughput,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            turnLlmCalls = llmCalls;
            turnThinkingMs = thinkingMs;
            turnToolSumMs = toolSumMs;
            turnToolWallMs = toolWallMs;
            turnToolsRun = toolsRun;
            this.turnDurationMs = turnDurationMs;
            turnThroughput = throughput == null ? AgentEvent.Throughput.EMPTY : throughput;
        } else if (event
                instanceof
                AgentEvent.TurnDone(String content, String sessionId, String subagentId, String subagentName)) {
            flushThinking();
            // split any inline <think> reasoning out of the final content so it renders
            // as a thought region instead of being dropped by markdown HTML handling
            final var parts = thinkTags.split(content);
            if (!parts.thinking().isBlank()) {
                transcript
                        .append("\n\n")
                        .append(THOUGHT_BEGIN)
                        .append("\n**Thinking**\n\n")
                        .append(parts.thinking().strip())
                        .append('\n')
                        .append(THOUGHT_END)
                        .append('\n');
            }
            // The final assistant message ("content") is authoritative but must never
            // truncate what already streamed into the pending tail. When the final content is a
            // prefix (or equals) what already streamed - a clipped provider reply - the fuller
            // streamed text wins and nothing is lost. Otherwise the final content extends the
            // stream or is a regeneration, so it supersedes the pending tail and is written
            // fresh (no duplication).
            final var streamed = pending.toString();
            final var visible = parts.visible();
            if (!streamed.isEmpty() && streamed.startsWith(visible)) {
                flushPending();
            } else {
                pending.setLength(0);
                if (!answerHeaderWritten) {
                    answerHeaderWritten = true;
                    transcript.append("\n\n" + assistantMarker() + "\n\n");
                } else {
                    transcript.append("\n\n");
                }
                // the final message is authoritative but is never persisted raw: a flash-class model
                // can echo a bare "@N" anchor (without its closing '@') directly in the final
                // payload and it must not freeze into the durable transcript
                transcript.append(compression.withholdDanglingPrefix(visible)).append('\n');
            }
            thinking.setLength(0);
            inlineThink.reset();
            answerHeaderWritten = false;
            busy = false;
            status = "ready";
            touchTranscript();
            touch();
        } else if (event
                instanceof AgentEvent.TurnCancelled(String sessionId, String subagentId, String subagentName)) {
            // a user interrupt ends the turn cleanly: flush the streamed answer/thinking so it
            // stays in the transcript, but do not write an error line nor mark the turn red -
            // cancellation is a normal (user-driven) end, not a failure
            flushThinking();
            flushPending();
            inlineThink.reset();
            answerHeaderWritten = false;
            busy = false;
            status = "cancelled";
            touchTranscript();
            touch();
        } else if (event
                instanceof AgentEvent.Error(String message, String sessionId, String subagentId, String subagentName)) {
            // keep the reasoning readable even when the turn fails
            flushThinking();
            // ... and the answer it already streamed, above the error line
            flushPending();
            inlineThink.reset();
            transcript.append("\n\n> error: ").append(message).append("\n");
            answerHeaderWritten = false;
            busy = false;
            status = "error: " + message;
            retryableErrorSeen = isRetryableLlmError(message);
            touchTranscript();
            touch();
        } else if (event
                instanceof
                AgentEvent.SubagentStarted(
                        String task,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        var _)) {
            // marker only: the subagent stream itself is captured separately (no inline
            // mixing of two model voices in one pane - see /subagents)
            final var label = subagentName == null || subagentName.isBlank() ? subagentId : subagentName;
            transcript
                    .append("\n\n> subagent `")
                    .append(label)
                    .append("` started: ")
                    .append(firstLine(task))
                    .append('\n');
            touchTranscript();
            touch();
        } else if (event
                instanceof
                AgentEvent.SubagentCompleted(
                        String summary,
                        boolean success,
                        boolean cancelled,
                        String sessionId,
                        String subagentId,
                        String subagentName,
                        String model)) {
            final var label = subagentName == null || subagentName.isBlank() ? subagentId : subagentName;
            transcript
                    .append("\n\n> subagent `")
                    .append(label)
                    .append("` ")
                    .append(success ? "completed" : cancelled ? "cancelled" : "failed");
            if (model != null && !model.isBlank()) {
                transcript.append(" — model `").append(model.replace("`", "'")).append('`');
            }
            transcript.append('\n');
            if (summary != null && !summary.isBlank()) {
                // the body is the raw summary rendered as real markdown (the TUI splits it back
                // into thought/tool/plain regions and the markdown renderer highlights it, fences
                // included), so it must not carry the blockquote prefix: only the header and the
                // pointer line are quoted, the body sits between them on plain lines
                transcript
                        .append('\n')
                        .append(shieldSpeakerMarkers(userMarker(), assistantMarker(), capSummary(summary)))
                        .append('\n');
            }
            transcript.append("\n> full report: /subagents ").append(subagentId).append('\n');
            touchTranscript();
            touch();
        }
    }

    /**
     * Writes the accumulated thinking of the current round into the transcript
     * (wrapped between {@code THOUGHT_BEGIN}/{@code THOUGHT_END} markers so it
     * renders dimmed and can be shown/hidden as a block) and clears the buffer.
     * Called before each tool execution and before the final assistant answer,
     * keeping the transcript chronological. Thinking is always retained in the
     * transcript whatever the current visibility toggle is so toggling never
     * loses data.
     */
    /**
     * Emits whatever the stream splitter still holds for the current utterance (a
     * trailing inline-code backtick, a fence tail or an unclosed {@code <think>}
     * whose delimiter was split across chunks) into the pending/thinking buffers
     * before they are flushed. The splitter deliberately holds back a bounded
     * suffix of each delta waiting for a possible delimiter continuation, and
     * without this drain that carry would leak into the transcript of the next
     * message (a {@code ` :} residual appearing at the start of the following
     * assistant turn) while the current message loses its tail.
     */
    private void drainInlineThink() {
        for (final var segment : inlineThink.flush()) {
            if (segment.channel() == ThinkTagSplitter.Channel.THINKING) {
                thinking.append(segment.text());
            } else {
                pending.append(segment.text());
            }
        }
    }

    public synchronized void flushThinking() {
        drainInlineThink();
        if (thinking.isEmpty()) {
            return;
        }
        // the thinking buffer can carry a leaked bare seam anchor the live render would have
        // withheld: scrub it on the flush path too so the durable transcript matches what
        // was visible while it streamed
        final var thought =
                compression.withholdDanglingPrefix(thinking.toString()).strip();
        thinking.setLength(0);
        if (thought.isBlank()) {
            return;
        }
        transcript
                .append("\n\n")
                .append(THOUGHT_BEGIN)
                .append("\n**Thinking**\n\n")
                .append(thought)
                .append('\n')
                .append(THOUGHT_END)
                .append('\n');
        touchTranscript();
        touch();
    }

    /**
     * Writes the answer streamed so far into the transcript, in place, and clears
     * the buffer. The reasoning counterpart is {@link #flushThinking()}; both are
     * called at the turn boundaries (a tool call, an integrated prompt, a failed
     * turn) because {@code markdown()} only ever renders the pending tail at the
     * very end of the pane: without this the tool blocks and the thoughts
     * streamed in between would be rendered above the answer they followed, and
     * everything the model said before calling a tool would be lost when the turn
     * ends (only the last assistant message reaches {@code TurnDone}).
     * <p>
     * Deliberately not called on {@code TextDelta} (that would close a paragraph
     * per delta) nor on {@code TurnDone} / {@code TextReset}, where the pending
     * tail is superseded by the full content the provider sends again.
     * <p>
     * The {@code **Assistant**} label is written once per turn: a turn streams its
     * answer in as many chunks as it has reasoning rounds, and they are all part
     * of the same answer.
     */
    public synchronized void flushPending() {
        drainInlineThink();
        if (pending.isEmpty()) {
            return;
        }
        final var raw = pending.toString().stripTrailing();
        pending.setLength(0);
        if (raw.isBlank()) {
            return;
        }
        // a seam tag can split across a flush boundary (the streamer emits "@1250" then "4@ done"):
        // withholding the trailing partial-ref fragment into pending lets the next delta compose
        // the complete tag, which the normal scrub then removes. Freezing it here would persist
        // the fragment AND duplicate it when the authoritative TurnDone content supersedes the
        // streamed tail (streamed not being a prefix of the final content).
        final var split = splitTrailingSeamFragment(raw);
        final var text = split.text();
        if (split.fragment() != null) {
            pending.append(split.fragment());
        }
        // the flush freezes the tail into the durable transcript: strip complete echoed seams
        // AND withhold a trailing half-arrived seam fragment, exactly like the live render did,
        // so what is frozen matches what the user saw while it was streaming
        final var sanitized = compression.withholdDanglingPrefix(text).stripTrailing();
        if (sanitized.isBlank()) {
            return;
        }
        transcript.append("\n\n");
        if (!answerHeaderWritten) {
            answerHeaderWritten = true;
            transcript.append(assistantMarker() + "\n\n");
        }
        transcript.append(sanitized).append('\n');
        touchTranscript();
        touch();
    }

    /** Trailing partial-ref fragment to keep pending, or {@code null} when the text has none.
     * Matches the first half of a split tag ("@1250", "@b7", lone "@") and its mirror half
     * ("4@") at the very end of the text, provided it starts at a token boundary so real content
     * ("email@x", "version 2@") is never eaten. */
    private static SplitTrailing splitTrailingSeamFragment(final String text) {
        final var matcher = TRAILING_SEAM_FRAGMENT.matcher(text);
        if (!matcher.find()) {
            return new SplitTrailing(text, null);
        }
        final var start = matcher.start();
        if (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            return new SplitTrailing(text, null);
        }
        return new SplitTrailing(text.substring(0, start), matcher.group());
    }

    private record SplitTrailing(String text, String fragment) {}

    public synchronized String markdown(final String spinner, final String fallback) {
        // hot path: nothing changed since the last build -> reuse the cached string
        // instead of re-concatenating the whole transcript on every render tick
        if (markdownCache != null && markdownVersion == contentVersion && Objects.equals(markdownSpinner, spinner)) {
            return markdownCache;
        }
        final var built = buildMarkdown(spinner);
        final var result = built.md().isBlank() ? fallback : built.md();
        markdownVersion = contentVersion;
        markdownSpinner = spinner;
        markdownCache = result;
        return result;
    }

    /**
     * Same assembly as {@link #markdown(String, String)} but exposes the stable durable
     * {@code head} (the exact instance of the scrubbed transcript, so a streaming turn that
     * appends only to the pending tail reuses the very same {@code String} object frame after
     * frame - identity comparison, no hashing) and the {@code tailStart} offset where the
     * streaming content (pending tail, running-tool blocks) begins in the assembled md. The
     * TUI renderer keys its per-region render-unit cache on these: a frame whose head string
     * is identical proves that everything before the tail rendered the same as the previous
     * frame, so only the growing last region must be re-rendered instead of the whole
     * multi-megabyte transcript every TextDelta tick.
     */
    public synchronized MarkdownSnapshot markdownWithHead(final String spinner, final String fallback) {
        if (markdownHeadCache != null
                && markdownVersion == contentVersion
                && Objects.equals(markdownSpinner, spinner)) {
            return markdownHeadCache;
        }
        final var built = buildMarkdown(spinner);
        final var snapshot = new MarkdownSnapshot(
                scrubbedHeadCache, built.md().isBlank() ? fallback : built.md(), built.tailStart());
        markdownVersion = contentVersion;
        markdownSpinner = spinner;
        markdownCache = snapshot.md();
        markdownHeadCache = snapshot;
        return snapshot;
    }

    /** Assembled markdown plus where the streaming tail starts in it, see {@link #buildMarkdown}. */
    private record MarkdownBuild(String md, int tailStart) {}

    /**
     * Assembles the markdown for {@link #markdown} / {@link #markdownWithHead}: the scrubbed
     * durable head, then the pending tail and one running-tool block per pending tool. Also
     * computes {@code tailStart}, the index in the stripped md where the first streaming
     * character lands between the durable head and the appended tail.
     */
    private MarkdownBuild buildMarkdown(final String spinner) {
        // The durable transcript was already scrubbed on each write (flushPending, TurnDone,
        // flushThinking, ToolFinished, Notice... see touchTranscript() sites), so the scrub is
        // idempotent for it. The result is cached against transcriptVersion, which only bumps on
        // durable mutations: during a streaming turn every TextDelta bumps contentVersion (tail
        // moved) but not transcriptVersion, so the head below is reused frame after frame instead
        // of re-running withholdDanglingPrefix over the whole multi-megabyte transcript - the
        // busy-frame CPU hot spot.
        if (scrubbedHeadVersion != transcriptVersion) {
            scrubbedHeadCache = compression.withholdDanglingPrefix(transcript.toString());
            scrubbedHeadVersion = transcriptVersion;
        }
        final var sb = new StringBuilder(scrubbedHeadCache);
        final var headLen = sb.length();
        if (busy && !pending.isEmpty()) {
            // pending only carries visible text: inline  thinking content is already
            // classified into the thinking buffer by the stream splitter. It renders
            // last because that is where it happened: everything the turn produced
            // before this chunk was already flushed into the transcript in place.
            sb.append("\n\n");
            if (!answerHeaderWritten) {
                // same turn-scoped label as flushPending(): a turn streams one answer
                sb.append(assistantMarker() + "\n\n");
            }
            // the pending tail is sanitized BEFORE the running-tool blocks are appended: a
            // half-arrived seam fragment must stay hidden even once UI text follows it
            sb.append(withholdPendingTail());
        }
        // one running block per pending tool: parallel read-only tools each show
        // their own command and streamed output instead of aliasing a single slot.
        // the header carries the (#ordinal) stamped by the runner on ToolStarted so
        // the TUI can anchor the live block (clickable running-tool overlay, per-tool
        // cancel) before the call finishes
        for (final var tool :
                runningTools.values().stream().flatMap(Collection::stream).toList()) {
            // the running block is assembly-local: it embeds tool command/output that was never
            // scrubbed at write (only the finished capture is), so scrub the assembled block with
            // the same whole-string pass, preserving exact parity with the pre-split rendering
            final var block = new StringBuilder("\n\n")
                    .append(TOOL_BEGIN)
                    .append("\n`")
                    .append(spinner)
                    .append(" running ")
                    .append(tool.command == null || tool.command.isBlank() ? tool.name : tool.command)
                    .append(tool.ordinal() > 0 ? " (#" + tool.ordinal() + ")" : "")
                    .append("`");
            if (!tool.stream.isEmpty()) {
                block.append("\n\n```text\n")
                        .append(tool.stream.toString().stripTrailing())
                        .append("\n```\n");
            }
            block.append(TOOL_END).append('\n');
            sb.append(compression.withholdDanglingPrefix(block.toString()));
        }
        final var assembled = sb.toString();
        final var md = assembled.strip();
        // the streaming tail always starts after the durable head; strip() only ever trims
        // leading whitespace, which belongs to the head, so the tail offset is headLen minus
        // however many leading whitespace characters were stripped
        var lead = 0;
        while (lead < assembled.length() && Character.isWhitespace(assembled.charAt(lead))) {
            lead++;
        }
        return new MarkdownBuild(md, Math.max(0, headLen - lead));
    }

    public record Region(String text, boolean thought, boolean tool, boolean log) {}

    /**
     * Splits the transcript markdown into typed regions based on the
     * {@code THOUGHT_BEGIN}/{@code THOUGHT_END} markers emitted by
     * {@link #flushThinking} (rendered dimmed), the
     * {@code TOOL_BEGIN}/{@code TOOL_END} markers wrapping tool actions and
     * output (rendered even more subdued) and the {@code LOG_BEGIN}/
     * {@code LOG_END} markers wrapping captured log records (rendered by the TUI
     * with their own styling). Text outside markers is regular content.
     */
    public static List<Region> regions(final String md) {
        final var out = new ArrayList<Region>();
        int from = 0;
        while (true) {
            final var thought = md.indexOf(THOUGHT_BEGIN, from);
            final var tool = md.indexOf(TOOL_BEGIN, from);
            final var log = md.indexOf(LOG_BEGIN, from);
            if (thought < 0 && tool < 0 && log < 0) {
                out.add(new Region(md.substring(from), false, false, false));
                return out;
            }
            final var isThought = thought >= 0 && (tool < 0 || thought < tool) && (log < 0 || thought < log);
            final var isLog = !isThought && log >= 0 && (tool < 0 || log < tool);
            final var begin = isThought ? thought : isLog ? log : tool;
            final var beginMarker = isThought ? THOUGHT_BEGIN : isLog ? LOG_BEGIN : TOOL_BEGIN;
            final var endMarker = isThought ? THOUGHT_END : isLog ? LOG_END : TOOL_END;
            out.add(new Region(md.substring(from, begin), false, false, false));
            final var end = md.indexOf(endMarker, begin + beginMarker.length());
            final var inner = end < 0
                    ? md.substring(begin + beginMarker.length())
                    : md.substring(begin + beginMarker.length(), end);
            out.add(new Region(inner, isThought, !isThought && !isLog, isLog));
            if (end < 0) {
                return out;
            }
            from = end + endMarker.length();
        }
    }

    /**
     * Head-truncates the persisted transcript once it grows beyond
     * {@code maxHistoryCharacters}, keeping the tail of the conversation.
     * Everything stays in memory between turns; only the rendered history is
     * capped. The cut is aligned on a newline and any thought/tool/log region left
     * incomplete by the truncation (pair straddling the cut) is dropped so the
     * renderer never sees a dangling marker.
     */
    public synchronized void trim() {
        final var max = maxHistoryCharacters;
        if (max <= 0 || transcript.length() <= max) {
            return;
        }
        final var md = transcript.toString();
        var cut = md.length() - (int) Math.min(max, md.length());
        final var nl = md.indexOf('\n', cut);
        cut = nl < 0 ? md.length() : nl + 1;
        var kept = md.substring(cut);
        kept = dropDanglingRegion(kept, THOUGHT_BEGIN, THOUGHT_END);
        kept = dropDanglingRegion(kept, TOOL_BEGIN, TOOL_END);
        kept = dropDanglingRegion(kept, LOG_BEGIN, LOG_END);
        transcript.setLength(0);
        transcript.append(kept.stripLeading());
        touchTranscript();
        touch();
    }

    /**
     * Removes a region left incomplete by the truncation: an end marker
     * without its begin marker (the pair straddled the cut) or a begin marker
     * without its end marker (an append was interrupted). Regions never nest
     * so a single pass per marker pair is enough.
     */
    private static String dropDanglingRegion(final String md, final String begin, final String end) {
        var out = md;
        final var firstBegin = out.indexOf(begin);
        final var firstEnd = out.indexOf(end);
        if (firstEnd >= 0 && (firstBegin < 0 || firstEnd < firstBegin)) {
            out = out.substring(firstEnd + end.length());
        }
        final var lastBegin = out.lastIndexOf(begin);
        final var lastEnd = out.lastIndexOf(end);
        if (lastBegin >= 0 && (lastEnd < 0 || lastBegin > lastEnd)) {
            out = out.substring(0, lastBegin);
        }
        return out;
    }

    private static String toolCommand(final String argsJson) {
        if (argsJson == null || argsJson.isBlank() || argsJson.equals("{}")) {
            return "";
        }
        final var sb = new StringBuilder();
        final var stripped = argsJson.strip();
        for (final var key : List.of("command", "path", "workdir")) {
            final var token = "\"" + key + "\":";
            final var idx = stripped.indexOf(token);
            if (idx < 0) {
                continue;
            }
            final var start = idx + token.length();
            final var quote = stripped.indexOf('"', start);
            if (quote < 0) {
                continue;
            }
            final var end = stripped.indexOf('"', quote + 1);
            if (end < 0) {
                continue;
            }
            sb.append(stripped, quote + 1, end).append(' ');
        }
        return sb.toString().strip();
    }

    /** Caps a non-streamed tool output before it is written into the transcript, mirroring the streaming cap. */
    private static String capToolOutput(final String output) {
        final var text = output.stripTrailing();
        if (text.length() <= MAX_EMBEDDED_TOOL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_EMBEDDED_TOOL_CHARS) + "\n... output truncated";
    }

    /**
     * Appends a persisted session history to the transcript: user messages
     * render as {@code **You**} blocks, assistant messages as
     * {@code **Assistant**} blocks and tool results as single-line italics.
     * Callers clear the transcript first when the replay must replace the
     * current conversation.
     *
     * @return the appended markdown, so printing frontends can render it
     *         without re-reading the transcript
     */
    public synchronized String replay(final List<Message> history) {
        if (history == null) {
            return "";
        }
        final var start = transcript.length();
        final var toolNamesByCallId = Message.toolNames(history);
        for (final var message : history) {
            switch (message.role()) {
                case "user" ->
                    transcript
                            .append("\n\n---\n\n" + markerFor(message, true) + "\n\n")
                            .append(String.valueOf(message.content()))
                            .append('\n');
                case "assistant" -> {
                    // persisted sessions keep the raw <think> content: split it so the
                    // replay renders past reasoning as thought regions too
                    final var parts = thinkTags.split(String.valueOf(message.content()));
                    if (!parts.thinking().isBlank()) {
                        transcript
                                .append("\n\n")
                                .append(THOUGHT_BEGIN)
                                .append("\n**Thinking**\n\n")
                                .append(parts.thinking().strip())
                                .append('\n')
                                .append(THOUGHT_END)
                                .append('\n');
                    }
                    transcript
                            .append("\n\n---\n\n" + markerFor(message, false) + "\n\n")
                            .append(parts.visible())
                            .append('\n');
                }
                case "tool" -> {
                    final var name = Message.toolName(message, toolNamesByCallId);
                    transcript
                            .append("\n\n*tool ")
                            .append(name == null ? "" : "`" + name + "` ")
                            .append("-> ")
                            .append(escapeMarkdown(firstLine(String.valueOf(message.content()))))
                            .append("*\n");
                }
                case "system" -> {
                    // persisted stop notices (iteration cap, exploration plateau): display-only.
                    // The leading system prompt is never rendered here, so only notice-shaped
                    // content (starting with the cap/plateau wording) is shown.
                    final var content = String.valueOf(message.content());
                    if (content.startsWith("Maximum iterations reached") || content.startsWith("Exploration plateau")) {
                        transcript.append("\n\n> ⚠ ").append(content).append('\n');
                    }
                }
                default -> {}
            }
        }
        touchTranscript();
        touch();
        return transcript.substring(start);
    }

    public synchronized void clearConversation() {
        transcript.setLength(0);
        thinking.setLength(0);
        inlineThink.reset();
        answerHeaderWritten = false;
        appliances.clear();
        touchTranscript();
        touch();
    }

    public synchronized void startUserTurn(final String text) {
        transcript.append("\n\n---\n\n" + userMarker() + "\n\n").append(text).append('\n');
        pending.setLength(0);
        thinking.setLength(0);
        inlineThink.reset();
        answerHeaderWritten = false;
        // NOTE: retryableErrorSeen is intentionally NOT cleared here: a stale
        // "error: ..." status from the previous turn must survive until the new
        // turn actually makes progress (the retry), then the auto-clear in
        // onEvent() drops it from the bar
        busy = true;
        touchTranscript();
        touch();
    }

    public synchronized void reset() {
        transcript.setLength(0);
        pending.setLength(0);
        thinking.setLength(0);
        inlineThink.reset();
        answerHeaderWritten = false;
        runningTools.clear();
        toolCallOrdinal = 0;
        retryableErrorSeen = false;
        busy = false;
        status = "";
        appliances.clear();
        promptTokens = 0;
        completionTokens = 0;
        cacheReadTokens = 0;
        estimatedContext = 0;
        lastRealPromptTokens = 0;
        calibrationRatio = -1;
        contextPercent = 0;
        quotaVisible = false;
        quotaPlanType = null;
        quotaUsedPercent = null;
        quotaWindowSeconds = 0;
        quotaResetAtEpochSeconds = 0;
        quotaWindowLabel = null;
        quotaCreditsBalance = null;
        quotaCreditsUnlimited = false;
        quotaWindows = List.of();
        maxHistoryCharacters = DEFAULT_MAX_HISTORY_CHARACTERS;
        turnLlmCalls = 0;
        turnThinkingMs = 0;
        turnToolSumMs = 0;
        turnToolWallMs = 0;
        turnToolsRun = 0;
        turnDurationMs = 0;
        turnThroughput = AgentEvent.Throughput.EMPTY;
        touchTranscript();
        touch();
    }

    /**
     * Appends raw markdown to the conversation under the transcript lock so it
     * never races with render-thread readers ({@code markdown} / {@code trim}).
     * Use this from worker threads instead of {@link #transcript()} which hand
     * out the live (unlocked) builder.
     */
    public synchronized void appendTranscript(final String markdown) {
        transcript.append(markdown);
        touchTranscript();
        touch();
    }

    public StringBuilder transcript() {
        return transcript;
    }

    public StringBuilder pending() {
        return pending;
    }

    public StringBuilder thinking() {
        return thinking;
    }

    public boolean busy() {
        return busy;
    }

    public void busy(final boolean value) {
        // the busy flag gates the pending tail in markdown(): a flip must invalidate the cache
        if (this.busy != value) {
            this.busy = value;
            touch();
        }
    }

    public String status() {
        return status;
    }

    /** Recent context appliances (dedup/prune/compact/...), newest first, capped for the sidebar. */
    public List<AgentEvent.Compacted> appliances() {
        synchronized (this) {
            return List.copyOf(appliances);
        }
    }

    private void recordAppliance(final AgentEvent.Compacted appliance) {
        synchronized (this) {
            appliances.addFirst(appliance);
            while (appliances.size() > MAX_APPLIANCES) {
                appliances.removeLast();
            }
        }
    }

    public void status(final String value) {
        this.status = value;
    }

    public int thinkingLength() {
        return thinking.length();
    }

    /**
     * Thread-safe snapshot of the live (not yet flushed) thinking buffer, for renderers. The buffer
     * is scrubbed of echoed compact seams (see {@link ContextCompression#stripEchoedRefs}) because
     * this surface bypasses {@link #markdown(String, String)} (which scrubs flushed thoughts): a
     * flash-class model echoing the injected {@code @N@} seam in its live reasoning would otherwise
     * render verbatim while the stored message stays clean.
     */
    public synchronized String thinkingText() {
        return compression.withholdDanglingPrefix(thinking.toString());
    }

    /** The seam-scrub source used for live render surfaces (see {@link #compression}). */
    public ContextCompression compression() {
        return compression;
    }

    /**
     * Rebinds the transcript to the shared {@link ContextCompression} once its owning session runtime
     * is created (so the dangling-seam withhold keys off the refs the agent loop actually renders).
     * A null argument resets to a fresh instance; the previous instance is discarded, never mutated.
     */
    public synchronized void bindCompression(final ContextCompression value) {
        this.compression = value != null ? value : new ContextCompression(null);
        // the pending tail, the durable head and the cache were sanitized against the previous
        // instance's refs: drop them so the next render rebuilds against the new refs
        markdownCache = null;
        scrubbedHeadCache = null;
        scrubbedHeadVersion = -1;
    }

    /**
     * The pending tail (and only it, not the durable transcript) with complete echoed seams
     * stripped and a trailing half-arrived seam fragment withheld, so the fragment stays hidden
     * while it waits for its remainder and never gets frozen into the transcript by a flush.
     */
    private String withholdPendingTail() {
        return compression.withholdDanglingPrefix(pending.toString());
    }

    public String runningTool() {
        final var newest = newestRunningTool();
        return newest == null ? null : newest.name;
    }

    public String runningToolCommand() {
        final var newest = newestRunningTool();
        return newest == null ? null : newest.command;
    }

    /**
     * An immutable snapshot of one in-flight tool execution: its name, the
     * command label (bash command when the tool runs one, else the tool name),
     * the {@code #N} ordinal and the elapsed wall-clock milliseconds.
     */
    public record RunningToolInfo(String name, String command, long ordinal, long elapsedMs) {}

    /**
     * Snapshot of every tool currently executing (most recently started first),
     * defensive-copied so callers never touch the live buffers. Empty when no
     * tool is in flight.
     */
    public synchronized List<RunningToolInfo> runningToolsSnapshot() {
        final var now = System.nanoTime();
        return runningTools.values().stream()
                .flatMap(Collection::stream)
                .map(t -> new RunningToolInfo(
                        t.name, t.command, t.ordinal, Math.max(0, (now - t.startedNanos) / 1_000_000L)))
                .sorted(Comparator.comparingLong(RunningToolInfo::ordinal).reversed())
                .toList();
    }

    /** The most recently started pending tool, for the status/spinner display. */
    private RunningTool newestRunningTool() {
        RunningTool newest = null;
        long newestNanos = Long.MIN_VALUE;
        for (final var deque : runningTools.values()) {
            final var tail = deque.peekLast();
            if (tail != null && tail.startedNanos > newestNanos) {
                newest = tail;
                newestNanos = tail.startedNanos;
            }
        }
        return newest;
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    /** Provider-served prompt tokens that were read from the cache (subset of {@link #promptTokens()}). */
    public long cacheReadTokens() {
        return cacheReadTokens;
    }

    public int estimatedContext() {
        return estimatedContext;
    }

    /** Provider-billed prompt tokens of the last anchored request; {@code 0} until the first LLM response (no anchor). */
    public int lastRealPromptTokens() {
        return lastRealPromptTokens;
    }

    /** Clamped calibration ratio of the anchored estimate; {@code -1} until the first LLM response (no anchor). */
    public double calibrationRatio() {
        return calibrationRatio;
    }

    public int contextPercent() {
        return contextPercent;
    }

    /** Whether the current provider exposes a visible account quota (plan + primary window). */
    public boolean quotaVisible() {
        return quotaVisible;
    }

    public String quotaPlanType() {
        return quotaPlanType;
    }

    /** The primary-window used percentage (0-100), {@code 0} when unsupported. */
    public double quotaUsedPercent() {
        return quotaUsedPercent == null ? 0 : quotaUsedPercent;
    }

    public Double quotaUsedPercentValue() {
        return quotaUsedPercent;
    }

    public long quotaWindowSeconds() {
        return quotaWindowSeconds;
    }

    /** Epoch seconds of the next quota reset, {@code 0} when unknown. */
    public long quotaResetAtEpochSeconds() {
        return quotaResetAtEpochSeconds;
    }

    public String quotaWindowLabel() {
        return quotaWindowLabel;
    }

    public String quotaCreditsBalance() {
        return quotaCreditsBalance;
    }

    public boolean quotaCreditsUnlimited() {
        return quotaCreditsUnlimited;
    }

    protected List<AgentEvent.UsageUpdated.Quota.Window> quotaWindows() {
        return quotaWindows;
    }

    public int turnLlmCalls() {
        return turnLlmCalls;
    }

    public long turnThinkingMs() {
        return turnThinkingMs;
    }

    public long turnToolSumMs() {
        return turnToolSumMs;
    }

    public long turnToolWallMs() {
        return turnToolWallMs;
    }

    public int turnToolsRun() {
        return turnToolsRun;
    }

    /**
     * Output throughput of the current turn.
     *
     * @return the throughput, never {@code null} ({@link AgentEvent.Throughput#EMPTY} when unknown).
     */
    public AgentEvent.Throughput turnThroughput() {
        return turnThroughput;
    }

    /**
     * @return the turn output tokens per second, {@code 0} when not measurable.
     */
    public double turnOutputTokensPerSecond() {
        return turnThroughput.tokensPerSecond();
    }

    /** One in-flight tool execution: its command label, streamed output and start clock. */
    private record RunningTool(String name, String command, StringBuilder stream, long startedNanos, long ordinal) {
        private RunningTool(final String name, final String command, final long ordinal) {
            this(name, command, new StringBuilder(), System.nanoTime(), ordinal);
        }
    }

    /** @return the 1-based ordinal of the last finished tool call, used to key tool cards. */
    public synchronized long toolCallCount() {
        return toolCallOrdinal;
    }

    public long turnDurationMs() {
        return turnDurationMs;
    }

    public long maxHistoryCharacters() {
        return maxHistoryCharacters;
    }

    public void maxHistoryCharacters(final long value) {
        this.maxHistoryCharacters = value;
    }

    /** First line of a text, capped at 120 characters; null-safe. */
    static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var idx = text.indexOf('\n');
        final var line = idx < 0 ? text : text.substring(0, idx);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    /**
     * Caps the sub-agent report body to a pane-safe size, cutting on a line boundary so a partial
     * markdown fence is never left open by the truncation. A trailing {@code \n...} marks the
     * truncation; the full report stays reachable through {@code /subagents <id>}.
     */
    private static String capSummary(final String summary) {
        final var cap = 4000;
        if (summary.length() <= cap) {
            return summary;
        }
        final var cut = summary.lastIndexOf('\n', cap);
        return summary.substring(0, cut > cap / 2 ? cut : cap).stripTrailing() + "\n...";
    }

    /**
     * A raw sub-agent report body is embedded into the parent transcript as plain markdown between
     * the quoted header/pointer lines. A body line holding nothing but a speaker marker
     * ({@code **You**} / {@code **Assistant**}, timestamped variants included) would be interpreted
     * by the TUI turn splitter as a turn boundary and split the pane, so such lines are shielded
     * with a leading non-breaking space to keep them literal.
     */
    private static String shieldSpeakerMarkers(
            final String userMarker, final String assistantMarker, final String text) {
        final var lines = text.split("\n", -1);
        final var sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            final var line = lines[i];
            if (isSpeakerMarkerLine(line, userMarker) || isSpeakerMarkerLine(line, assistantMarker)) {
                sb.append('\u00A0').append(line);
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** True when a line holds nothing but the given speaker marker, timestamped variant included. */
    private static boolean isSpeakerMarkerLine(final String line, final String marker) {
        if (marker == null || line == null) {
            return false;
        }
        final var stripped = line.strip();
        if (stripped.equals(marker)) {
            return true;
        }
        if (stripped.startsWith("**[") && stripped.endsWith("**")) {
            final var close = stripped.indexOf(']');
            if (close > 2) {
                return unwrapFences(stripped.substring(close + 1).stripLeading())
                        .equals(unwrapFences(marker));
            }
        }
        return false;
    }

    /** Strips the {@code **} fences of a marker line; returns the raw line when not fenced. */
    private static String unwrapFences(final String marker) {
        if (marker == null || marker.length() < 4 || !marker.startsWith("**") || !marker.endsWith("**")) {
            return marker;
        }
        return marker.substring(2, marker.length() - 2);
    }

    /**
     * Flattens a command onto one line before it is embedded in a {@code *streaming ...*}
     * header: newlines and carriage returns become single spaces so the markdown italic
     * paragraph cannot be kept open across softbreaks (a multi-line heredoc would glue the
     * remaining header lines into the following tool block).
     */
    private static String flatten(final String text) {
        if (text == null || text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        final var sb = new StringBuilder(text.length());
        var pendingSpace = false;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                pendingSpace = true;
            } else {
                if (pendingSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                pendingSpace = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes the markdown inline metacharacters of the tool header so a renderer keeps
     * them literal: {@code *} → {@code \\*}, {@code _} → {@code \\_} and {@code \\} →
     * {@code \\\\}. This is the exact inverse of {@code ToolCard.unescape}, which restores
     * the raw command when the TUI parses the header back. Only the display copy is
     * escaped; {@code RunningTool.command} keeps the raw bytes.
     */
    private static String escapeMarkdown(final String text) {
        if (text == null || text.indexOf('*') < 0 && text.indexOf('_') < 0 && text.indexOf('\\') < 0) {
            return text;
        }
        final var sb = new StringBuilder(text.length() + 8);
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '*' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
