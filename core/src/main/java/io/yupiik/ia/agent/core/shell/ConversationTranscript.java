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
import java.util.ArrayList;
import java.util.List;

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
    private final StringBuilder toolStream = new StringBuilder();
    private volatile String runningTool;
    private volatile String runningToolCommand;
    private long toolStartedNanos;
    private long toolCallOrdinal;
    private volatile boolean busy;
    private volatile String status = "";
    private volatile int promptTokens;
    private volatile int completionTokens;
    private volatile int estimatedContext;
    private volatile int contextPercent;
    private volatile long maxHistoryCharacters = DEFAULT_MAX_HISTORY_CHARACTERS;

    /** Splits GLM-style inline {@code <think>} reasoning out of the text channel. */
    protected final ThinkTagSplitter thinkTags;

    private final ThinkTagSplitter.Parser inlineThink;

    public ConversationTranscript() {
        this(new ThinkTagSplitter());
    }

    protected ConversationTranscript(final ThinkTagSplitter thinkTags) {
        // defensive: hand-built instances (tests, generated subclasses) may pass null
        this.thinkTags = thinkTags != null ? thinkTags : new ThinkTagSplitter();
        this.inlineThink = this.thinkTags.create();
    }

    // Per-turn metrics
    private volatile int turnLlmCalls;
    private volatile long turnThinkingMs;
    private volatile long turnToolSumMs;
    private volatile long turnToolWallMs;
    private volatile int turnToolsRun;
    private volatile long turnDurationMs;

    public synchronized void onEvent(final AgentEvent event) {
        if (event instanceof AgentEvent.TextDelta(String delta, String subagentId, String subagentName)) {
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
        } else if (event instanceof AgentEvent.TextReset(String subagentId, String subagentName)) {
            pending.setLength(0);
            inlineThink.reset();
            // keep the reasoning visible: flush instead of dropping it, the model resets
            // its output right before the final answer streams
            flushThinking();
        } else if (event instanceof AgentEvent.ThinkingDelta(String delta, String subagentId, String subagentName)) {
            thinking.append(delta);
        } else if (event
                instanceof AgentEvent.ToolStarted(String name, String args, String subagentId, String subagentName)) {
            flushThinking();
            runningTool = name;
            runningToolCommand = toolCommand(args);
            toolStream.setLength(0);
            toolStartedNanos = System.nanoTime();
        } else if (event
                instanceof
                AgentEvent.ToolStream(String name, String fragment, String subagentId, String subagentName)) {
            if (name.equals(runningTool)) {
                toolStream.append(fragment).append('\n');
                if (toolStream.length() > MAX_EMBEDDED_TOOL_CHARS) {
                    toolStream.delete(0, toolStream.length() - MAX_EMBEDDED_TOOL_CHARS);
                }
            }
        } else if (event
                instanceof
                AgentEvent.ToolFinished(
                        String name,
                        String output,
                        boolean success,
                        String subagentId,
                        String subagentName)) {
            final var running = runningTool;
            final var command = runningToolCommand;
            runningTool = null;
            runningToolCommand = null;
            final var elapsedMs = Math.max(0, (System.nanoTime() - toolStartedNanos) / 1_000_000);
            final var ordinal = ++toolCallOrdinal;
            if (running != null && (!toolStream.isEmpty() || (success && output != null && !output.isBlank()))) {
                final var header = command == null || command.isBlank()
                        ? "*streaming `" + running + "`* (#" + ordinal + ", " + elapsedMs + " ms)"
                        : "*streaming `" + running + "`: " + command.replace('`', '\'') + "* (#" + ordinal + ", "
                                + elapsedMs + " ms)";
                final var captured = toolStream.isEmpty()
                        ? capToolOutput(output == null ? "" : output)
                        : toolStream.toString().stripTrailing();
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
            toolStream.setLength(0);
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
        } else if (event
                instanceof
                AgentEvent.UsageUpdated(
                        io.yupiik.ia.agent.core.llm.Usage usage,
                        int estimatedContextTokens,
                        double percent,
                        String subagentId,
                        String subagentName)) {
            promptTokens = usage == null ? 0 : usage.promptTokens();
            completionTokens = usage == null ? 0 : usage.completionTokens();
            estimatedContext = estimatedContextTokens;
            contextPercent = (int) Math.round(percent);
        } else if (event
                instanceof AgentEvent.Compacted(String kind, String mode, String subagentId, String subagentName)) {
            status = "context " + kind + " done (" + mode.toLowerCase(java.util.Locale.ROOT) + ")";
            transcript
                    .append("\n\n> context ")
                    .append(kind)
                    .append(" applied (")
                    .append(mode.toLowerCase(java.util.Locale.ROOT))
                    .append(")\n");
        } else if (event
                instanceof
                AgentEvent.Notice(
                        AgentEvent.Notice.Level level,
                        String message,
                        String finishReason,
                        String subagentId,
                        String subagentName)) {
            final var tag =
                    switch (level) {
                        case ERROR -> "⚠ error";
                        case WARN -> "⚠ warning";
                        case INFO -> "ⓘ";
                    };
            transcript.append("\n\n> ").append(tag).append(": ").append(message).append("\n");
            if (level == AgentEvent.Notice.Level.ERROR) {
                status = "error: " + message;
            } else {
                status = message;
            }
        } else if (event instanceof AgentEvent.Status(String message, String subagentId, String subagentName)) {
            status = message;
        } else if (event instanceof AgentEvent.PromptIntegrated(String text, String subagentId, String subagentName)) {
            flushThinking();
            pending.setLength(0);
            thinking.setLength(0);
            inlineThink.reset();
            transcript.append("\n\n---\n\n**You**\n\n").append(text).append('\n');
            status = "queued prompt integrated";
        } else if (event
                instanceof
                AgentEvent.TurnMetrics(
                        int llmCalls,
                        long thinkingMs,
                        long toolSumMs,
                        long toolWallMs,
                        int toolsRun,
                        long turnDurationMs,
                        String subagentId,
                        String subagentName)) {
            turnLlmCalls = llmCalls;
            turnThinkingMs = thinkingMs;
            turnToolSumMs = toolSumMs;
            turnToolWallMs = toolWallMs;
            turnToolsRun = toolsRun;
            this.turnDurationMs = turnDurationMs;
        } else if (event instanceof AgentEvent.TurnDone(String content, String subagentId, String subagentName)) {
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
            transcript.append("\n\n**Assistant**\n\n").append(parts.visible()).append('\n');
            pending.setLength(0);
            thinking.setLength(0);
            inlineThink.reset();
            busy = false;
            status = "ready";
        } else if (event instanceof AgentEvent.Error(String message, String subagentId, String subagentName)) {
            // keep the reasoning readable even when the turn fails
            flushThinking();
            inlineThink.reset();
            transcript.append("\n\n> error: ").append(message).append("\n");
            pending.setLength(0);
            busy = false;
            status = "error: " + message;
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
    public synchronized void flushThinking() {
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
    }

    public synchronized String markdown(final String spinner, final String fallback) {
        final var sb = new StringBuilder(transcript);
        if (busy && !pending.isEmpty()) {
            // pending only carries visible text: inline <think> content is already
            // classified into the thinking buffer by the stream splitter
            sb.append("\n\n**Assistant**\n\n").append(pending);
        }
        if (runningTool != null) {
            sb.append("\n\n")
                    .append(TOOL_BEGIN)
                    .append("\n`")
                    .append(spinner)
                    .append(" running ")
                    .append(
                            runningToolCommand == null || runningToolCommand.isBlank()
                                    ? runningTool
                                    : runningToolCommand)
                    .append("`");
            if (!toolStream.isEmpty()) {
                sb.append("\n\n```text\n")
                        .append(toolStream.toString().stripTrailing())
                        .append("\n```\n");
            }
            sb.append(TOOL_END).append('\n');
        }
        final var md = sb.toString().strip();
        return md.isBlank() ? fallback : md;
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
                            .append("\n\n---\n\n**You**\n\n")
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
                            .append("\n\n---\n\n**Assistant**\n\n")
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
                default -> {}
            }
        }
        return transcript.substring(start);
    }

    public synchronized void clearConversation() {
        transcript.setLength(0);
        thinking.setLength(0);
        inlineThink.reset();
    }

    public synchronized void startUserTurn(final String text) {
        transcript.append("\n\n---\n\n**You**\n\n").append(text).append('\n');
        pending.setLength(0);
        thinking.setLength(0);
        inlineThink.reset();
        busy = true;
    }

    public synchronized void reset() {
        transcript.setLength(0);
        pending.setLength(0);
        thinking.setLength(0);
        toolStream.setLength(0);
        inlineThink.reset();
        runningTool = null;
        runningToolCommand = null;
        busy = false;
        status = "";
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
    }

    /**
     * Appends raw markdown to the conversation under the transcript lock so it
     * never races with render-thread readers ({@code markdown} / {@code trim}).
     * Use this from worker threads instead of {@link #transcript()} which hand
     * out the live (unlocked) builder.
     */
    public synchronized void appendTranscript(final String markdown) {
        transcript.append(markdown);
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
        this.busy = value;
    }

    public String status() {
        return status;
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
        return runningTool;
    }

    public String runningToolCommand() {
        return runningToolCommand;
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
