package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * UI-less conversation state of the terminal UI: transcript, streaming
 * buffers, status and usage counters. Agent events are folded in here while
 * the renderer only reads, which keeps this class testable without a terminal.
 */
@ApplicationScoped
public class TuiTranscriptModel {
    public static final String THOUGHT_BEGIN = "<<<yca:thought>>>";
    public static final String THOUGHT_END = "<<<yca:/thought>>>";
    public static final String TOOL_BEGIN = "<<<yca:tool>>>";
    public static final String TOOL_END = "<<<yca:/tool>>>";

    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;

    private final StringBuilder transcript = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder toolStream = new StringBuilder();
    private volatile String runningTool;
    private volatile String runningToolCommand;
    private long toolStartedNanos;
    private volatile boolean busy;
    private volatile String status = "";
    private volatile int scrollBack;
    private volatile int promptTokens;
    private volatile int completionTokens;
    private volatile int estimatedContext;
    private volatile int contextPercent;
    private volatile long maxHistoryCharacters = DEFAULT_MAX_HISTORY_CHARACTERS;

    public void onEvent(final AgentEvent event) {
        if (event instanceof AgentEvent.TextDelta(String delta)) {
            pending.append(delta);
            scrollBack = 0;
        } else if (event instanceof AgentEvent.ThinkingDelta(String delta)) {
            thinking.append(delta);
        } else if (event instanceof AgentEvent.ToolStarted(String name, String args)) {
            flushThinking();
            runningTool = name;
            runningToolCommand = toolCommand(args);
            toolStream.setLength(0);
            toolStartedNanos = System.nanoTime();
            scrollBack = 0;
        } else if (event instanceof AgentEvent.ToolStream(String name, String fragment)) {
            if (name.equals(runningTool)) {
                toolStream.append(fragment).append('\n');
                if (toolStream.length() > 1600) {
                    toolStream.delete(0, toolStream.length() - 1600);
                }
            }
        } else if (event instanceof AgentEvent.ToolFinished(String name, String output, boolean success)) {
            final var running = runningTool;
            final var command = runningToolCommand;
            runningTool = null;
            runningToolCommand = null;
            final var elapsedMs = Math.max(0, (System.nanoTime() - toolStartedNanos) / 1_000_000);
            if (running != null && !toolStream.isEmpty()) {
                final var header = command == null || command.isBlank()
                        ? "*streaming `" + running + "`* (" + elapsedMs + " ms)"
                        : "*streaming `" + running + "`: " + command.replace('`', '\'') + "* (" + elapsedMs + " ms)";
                transcript.append("\n\n").append(TOOL_BEGIN).append('\n').append(header).append("\n\n```text\n")
                        .append(toolStream.toString().stripTrailing()).append("\n```\n")
                        .append(TOOL_END).append('\n');
            }
            toolStream.setLength(0);
            if (!success) {
                transcript.append("\n\n").append(TOOL_BEGIN).append("\n> tool `").append(name).append("` failed: ")
                        .append(TuiInputSupport.firstLine(output)).append(" (").append(elapsedMs).append(" ms)\n")
                        .append(TOOL_END).append('\n');
            }
        } else if (event instanceof AgentEvent.UsageUpdated(
                io.yupiik.ia.agent.core.llm.Usage usage, int estimatedContextTokens, double percent
        )) {
            promptTokens = usage == null ? 0 : usage.promptTokens();
            completionTokens = usage == null ? 0 : usage.completionTokens();
            estimatedContext = estimatedContextTokens;
            contextPercent = (int) Math.round(percent);
        } else if (event instanceof AgentEvent.Compacted(String kind)) {
            status = "context " + kind + " done";
            transcript.append("\n\n> context ").append(kind).append(" applied\n");
        } else if (event instanceof AgentEvent.Status(String message)) {
            status = message;
        } else if (event instanceof AgentEvent.TurnDone(String content)) {
            flushThinking();
            transcript.append("\n\n**Assistant**\n\n").append(content).append('\n');
            pending.setLength(0);
            thinking.setLength(0);
            scrollBack = 0;
            busy = false;
            status = "ready";
        } else if (event instanceof AgentEvent.Error(String message)) {
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
     * transcript whatever the current {@code Ctrl+T} visibility is so toggling
     * never loses data.
     */
    public void flushThinking() {
        if (thinking.isEmpty()) {
            return;
        }
        final var thought = thinking.toString().strip();
        thinking.setLength(0);
        if (thought.isBlank()) {
            return;
        }
        transcript.append("\n\n").append(THOUGHT_BEGIN).append("\n**Thinking**\n\n")
                .append(thought).append('\n').append(THOUGHT_END).append('\n');
    }

    public String markdown(final String spinner, final String fallback) {
        final var sb = new StringBuilder(transcript);
        if (busy && !pending.isEmpty()) {
            sb.append("\n\n**Assistant**\n\n").append(pending);
        }
        if (runningTool != null) {
            sb.append("\n\n`").append(spinner).append(" running ")
                    .append(runningToolCommand == null || runningToolCommand.isBlank()
                            ? runningTool : runningToolCommand).append("`");
            if (!toolStream.isEmpty()) {
                sb.append("\n\n```text\n").append(toolStream.toString().stripTrailing()).append("```\n");
            }
        }
        final var md = sb.toString().strip();
        return md.isBlank() ? fallback : md;
    }

    public record Region(String text, boolean thought, boolean tool) {
    }

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
    public void trim() {
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

    public void clearConversation() {
        transcript.setLength(0);
        thinking.setLength(0);
        scrollBack = 0;
    }

    public void startUserTurn(final String text) {
        transcript.append("\n\n---\n\n**You**\n\n").append(text).append('\n');
        pending.setLength(0);
        thinking.setLength(0);
        busy = true;
        scrollBack = 0;
    }

    public void reset() {
        transcript.setLength(0);
        pending.setLength(0);
        thinking.setLength(0);
        toolStream.setLength(0);
        runningTool = null;
        runningToolCommand = null;
        busy = false;
        status = "";
        scrollBack = 0;
        promptTokens = 0;
        completionTokens = 0;
        estimatedContext = 0;
        contextPercent = 0;
        maxHistoryCharacters = DEFAULT_MAX_HISTORY_CHARACTERS;
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

    public int scrollBack() {
        return scrollBack;
    }

    public void scrollBack(final int value) {
        this.scrollBack = value;
    }

    public int thinkingLength() {
        return thinking.length();
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

    public long maxHistoryCharacters() {
        return maxHistoryCharacters;
    }

    public void maxHistoryCharacters(final long value) {
        this.maxHistoryCharacters = value;
    }
}
