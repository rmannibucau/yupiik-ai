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
import io.yupiik.ia.agent.core.llm.Message;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UI-less conversation state shared by the shells: transcript, streaming
 * buffers, status and usage counters. Agent events are folded in here while
 * the renderers only read, which keeps this class testable without a terminal.
 * <p>
 * The transcript holds markdown; thought and tool blocks are wrapped in
 * {@code THOUGHT_BEGIN}/{@code THOUGHT_END} and {@code TOOL_BEGIN}/
 * {@code TOOL_END} markers so frontends can render (or hide) them as typed
 * regions, see {@link #regions(String)}.
 */
public class ConversationTranscript {
    public static final String THOUGHT_BEGIN = "<<<yca:thought>>>";
    public static final String THOUGHT_END = "<<<yca:/thought>>>";
    public static final String TOOL_BEGIN = "<<<yca:tool>>>";
    public static final String TOOL_END = "<<<yca:/tool>>>";

    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;
    private static final int MAX_EMBEDDED_TOOL_CHARS = 4000;

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
    private volatile int estimatedContext;
    private volatile int contextPercent;
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
    private volatile java.util.function.BiFunction<Message.Identity, Boolean, String> labelResolver;

    public void setLabelResolver(final java.util.function.BiFunction<Message.Identity, Boolean, String> resolver) {
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

    private long markdownVersion = -1;
    private String markdownSpinner;
    private String markdownCache;

    /** Marks the transcript-markdown inputs as changed so {@code markdown()} rebuilds its cache. */
    private synchronized void touch() {
        contentVersion++;
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
        final var m = message.toLowerCase(java.util.Locale.ROOT);
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
        // defensive: hand-built instances (tests, generated subclasses) may pass null
        this.thinkTags = thinkTags != null ? thinkTags : new ThinkTagSplitter();
        this.inlineThink = this.thinkTags.create();
        this.captureSubagentEvents = captureSubagentEvents;
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
    private final java.util.ArrayDeque<AgentEvent.Compacted> appliances = new java.util.ArrayDeque<>(8);

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
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            final var deque = runningTools.get(name);
            if (deque != null && !deque.isEmpty()) {
                // the most recently started tool with this name is the live one
                final var tool = deque.peekLast();
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
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            final var deque = runningTools.get(name);
            final var tool = deque == null ? null : deque.pollFirst();
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
                final var header = tool.command == null || tool.command.isBlank()
                        ? "*streaming `" + name + "`* (#" + ordinal + ", " + elapsedMs + " ms)"
                        : "*streaming `" + name + "`: " + tool.command.replace('`', '\'') + "* (#" + ordinal + ", "
                                + elapsedMs + " ms)";
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
            if (!success) {
                transcript
                        .append("\n\n")
                        .append(TOOL_BEGIN)
                        .append("\n> tool `")
                        .append(name)
                        .append("` failed: ")
                        .append(firstLine(output))
                        .append(" (#")
                        .append(ordinal)
                        .append(", ")
                        .append(elapsedMs)
                        .append(" ms)\n")
                        .append(TOOL_END)
                        .append('\n');
            }
            touch();
        } else if (event
                instanceof
                AgentEvent.UsageUpdated(
                        io.yupiik.ia.agent.core.llm.Usage usage,
                        int estimatedContextTokens,
                        double percent,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            promptTokens = usage == null ? 0 : usage.promptTokens();
            completionTokens = usage == null ? 0 : usage.completionTokens();
            estimatedContext = estimatedContextTokens;
            contextPercent = (int) Math.round(percent);
        } else if (event
                instanceof
                AgentEvent.Compacted(
                        String kind,
                        String mode,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            // side feedback only: record the appliance for the sidebar instead of touching the
            // leading status, which must stay on the short busy/ready state (a stale "context
            // dedup done..." used to hide the current spinner/error at the bottom)
            recordAppliance(new AgentEvent.Compacted(kind, mode, sessionId, subagentId, subagentName));
            transcript
                    .append("\n\n> context ")
                    .append(kind)
                    .append(" applied (")
                    .append(mode.toLowerCase(java.util.Locale.ROOT))
                    .append(")\n");
            touch();
        } else if (event
                instanceof
                AgentEvent.Notice(
                        AgentEvent.Notice.Level level,
                        String message,
                        String finishReason,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            final var tag =
                    switch (level) {
                        case ERROR -> "⚠ error";
                        case WARN -> "⚠ warning";
                        case INFO -> "ⓘ";
                    };
            transcript.append("\n\n> ").append(tag).append(": ").append(message).append("\n");
            touch();
            retryableErrorSeen |= isRetryableLlmError(message);
            if (level == AgentEvent.Notice.Level.ERROR) {
                status = "error: " + message;
            } else {
                status = message;
            }
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
            thinking.setLength(0);
            inlineThink.reset();
            transcript
                    .append("\n\n---\n\n" + userMarker() + "\n\n")
                    .append(text)
                    .append('\n');
            status = "queued prompt integrated";
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
            // content is only the last assistant message: it supersedes the pending
            // tail of this round (already in the transcript from the flushes above),
            // so the label is only re-emitted when the turn never streamed text
            if (!answerHeaderWritten) {
                answerHeaderWritten = true;
                transcript.append("\n\n" + assistantMarker() + "\n\n");
            } else {
                transcript.append("\n\n");
            }
            transcript.append(parts.visible()).append('\n');
            pending.setLength(0);
            thinking.setLength(0);
            inlineThink.reset();
            answerHeaderWritten = false;
            busy = false;
            status = "ready";
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
            touch();
        } else if (event
                instanceof
                AgentEvent.SubagentStarted(String task, String sessionId, String subagentId, String subagentName)) {
            // marker only: the subagent stream itself is captured separately (no inline
            // mixing of two model voices in one pane - see /subagents)
            final var label = subagentName == null || subagentName.isBlank() ? subagentId : subagentName;
            transcript
                    .append("\n\n> subagent `")
                    .append(label)
                    .append("` started: ")
                    .append(firstLine(task))
                    .append('\n');
            touch();
        } else if (event
                instanceof
                AgentEvent.SubagentCompleted(
                        String summary,
                        boolean success,
                        boolean cancelled,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            final var label = subagentName == null || subagentName.isBlank() ? subagentId : subagentName;
            transcript
                    .append("\n\n> subagent `")
                    .append(label)
                    .append("` ")
                    .append(success ? "completed" : cancelled ? "cancelled" : "failed");
            if (summary != null && !summary.isBlank()) {
                transcript.append(": ").append(firstLine(summary));
            }
            transcript.append('\n');
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
        final var thought = thinking.toString().strip();
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
        final var text = pending.toString().stripTrailing();
        pending.setLength(0);
        if (text.isBlank()) {
            return;
        }
        transcript.append("\n\n");
        if (!answerHeaderWritten) {
            answerHeaderWritten = true;
            transcript.append(assistantMarker() + "\n\n");
        }
        transcript.append(text).append('\n');
        touch();
    }

    public synchronized String markdown(final String spinner, final String fallback) {
        // hot path: nothing changed since the last build -> reuse the cached string
        // instead of re-concatenating the whole transcript on every render tick
        if (markdownCache != null
                && markdownVersion == contentVersion
                && java.util.Objects.equals(markdownSpinner, spinner)) {
            return markdownCache;
        }
        final var sb = new StringBuilder(transcript);
        if (busy && !pending.isEmpty()) {
            // pending only carries visible text: inline <think> content is already
            // classified into the thinking buffer by the stream splitter. It renders
            // last because that is where it happened: everything the turn produced
            // before this chunk was already flushed into the transcript in place.
            sb.append("\n\n");
            if (!answerHeaderWritten) {
                // same turn-scoped label as flushPending(): a turn streams one answer
                sb.append(assistantMarker() + "\n\n");
            }
            sb.append(pending);
        }
        // one running block per pending tool: parallel read-only tools each show
        // their own command and streamed output instead of aliasing a single slot
        for (final var entry : runningTools.entrySet()) {
            for (final var tool : entry.getValue()) {
                sb.append("\n\n")
                        .append(TOOL_BEGIN)
                        .append("\n`")
                        .append(spinner)
                        .append(" running ")
                        .append(tool.command == null || tool.command.isBlank() ? tool.name : tool.command)
                        .append("`");
                if (!tool.stream.isEmpty()) {
                    sb.append("\n\n```text\n")
                            .append(tool.stream.toString().stripTrailing())
                            .append("\n```\n");
                }
                sb.append(TOOL_END).append('\n');
            }
        }
        final var md = sb.toString().strip();
        final var result = md.isBlank() ? fallback : md;
        markdownVersion = contentVersion;
        markdownSpinner = spinner;
        markdownCache = result;
        return result;
    }

    public record Region(String text, boolean thought, boolean tool) {}

    /**
     * Splits the transcript markdown into typed regions based on the
     * {@code THOUGHT_BEGIN}/{@code THOUGHT_END} markers emitted by
     * {@link #flushThinking} (rendered dimmed) and the
     * {@code TOOL_BEGIN}/{@code TOOL_END} markers wrapping tool actions and
     * output (rendered even more subdued). Text outside markers is regular
     * content.
     */
    public static List<Region> regions(final String md) {
        final var out = new ArrayList<Region>();
        int from = 0;
        while (true) {
            final var thought = md.indexOf(THOUGHT_BEGIN, from);
            final var tool = md.indexOf(TOOL_BEGIN, from);
            if (thought < 0 && tool < 0) {
                out.add(new Region(md.substring(from), false, false));
                return out;
            }
            final var isThought = thought >= 0 && (tool < 0 || thought < tool);
            final var begin = isThought ? thought : tool;
            final var beginMarker = isThought ? THOUGHT_BEGIN : TOOL_BEGIN;
            final var endMarker = isThought ? THOUGHT_END : TOOL_END;
            out.add(new Region(md.substring(from, begin), false, false));
            final var end = md.indexOf(endMarker, begin + beginMarker.length());
            final var inner = end < 0
                    ? md.substring(begin + beginMarker.length())
                    : md.substring(begin + beginMarker.length(), end);
            out.add(new Region(inner, isThought, !isThought));
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
     * capped. The cut is aligned on a newline and any thought/tool region left
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
        transcript.setLength(0);
        transcript.append(kept.stripLeading());
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
                            .append(firstLine(String.valueOf(message.content())))
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
        touch();
        return transcript.substring(start);
    }

    public synchronized void clearConversation() {
        transcript.setLength(0);
        thinking.setLength(0);
        inlineThink.reset();
        answerHeaderWritten = false;
        appliances.clear();
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
        estimatedContext = 0;
        contextPercent = 0;
        maxHistoryCharacters = DEFAULT_MAX_HISTORY_CHARACTERS;
        turnLlmCalls = 0;
        turnThinkingMs = 0;
        turnToolSumMs = 0;
        turnToolWallMs = 0;
        turnToolsRun = 0;
        turnDurationMs = 0;
        turnThroughput = AgentEvent.Throughput.EMPTY;
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
    public java.util.List<AgentEvent.Compacted> appliances() {
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

    /** Thread-safe snapshot of the live (not yet flushed) thinking buffer, for renderers. */
    public synchronized String thinkingText() {
        return thinking.toString();
    }

    public String runningTool() {
        final var newest = newestRunningTool();
        return newest == null ? null : newest.name;
    }

    public String runningToolCommand() {
        final var newest = newestRunningTool();
        return newest == null ? null : newest.command;
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

    public int estimatedContext() {
        return estimatedContext;
    }

    public int contextPercent() {
        return contextPercent;
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
}
