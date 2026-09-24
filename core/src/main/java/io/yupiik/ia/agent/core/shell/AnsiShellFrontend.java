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
import io.yupiik.ia.agent.core.cli.CliVerbosity;
import io.yupiik.ia.agent.core.cli.MarkdownColorizer;
import io.yupiik.ia.agent.core.cli.Spinner;
import io.yupiik.ia.agent.core.cli.StreamTextBuffer;
import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.todo.TodoItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ANSI terminal frontend shared by the {@code cli} command: streams the answer
 * on stdout through a line colorizer, renders tool activity and notices on
 * stderr, and drives a spinner while the agent is busy. Conversation text is
 * printed incrementally; tool output presentation follows the configured
 * {@link CliVerbosity}.
 */
public class AnsiShellFrontend implements ShellFrontend {
    private static final int DIFF_MAX_LINES = 5;

    private final MarkdownColorizer colorizer;
    private final Symbols symbols;
    private final ToolPresenter presenter;
    private final CliVerbosity verbosity;
    private final boolean tty;
    private final StreamTextBuffer textBuffer;
    private final TurnStats stats = new TurnStats();

    private volatile boolean showThinking;
    private volatile ToolOutputVisibility showToolOutput;
    private volatile boolean toolStreamed;
    /** Whether the dim 'thinking...' header was printed for the current reasoning round. */
    private boolean thinkingRoundOpen;
    /** Whether the last stderr emission (a thought delta) did not end with a newline. */
    private boolean stderrNeedsNewline;

    private Spinner spinner;
    /** Whether the frontend is inside a turn: async events (subagents, parallel tool workers)
     * can still stream after {@link #endTurn()}; they must not (re)start the spinner. */
    private volatile boolean turnActive;

    /**
     * Serializes the spinner lifecycle (start/stop/turn flag flips) between the
     * event threads: {@link #beginSpinner(String)} check-then-act and
     * {@link #endTurn()} used to race, letting a spinner started right after the
     * final stop run forever past the turn. Distinct from {@link #renderLock}:
     * {@link Spinner#stop()} joins the animation worker which needs
     * {@code renderLock} for its frames, so the two locks must never nest.
     */
    private final ReentrantLock spinnerLock = new ReentrantLock();

    private final ArrayDeque<AgentEvent> pendingEvents = new ArrayDeque<>();
    /** Guards {@link #pendingEvents}, {@link #suspended} and the suspension bookkeeping flags. */
    private final ReentrantLock pendingLock = new ReentrantLock();

    private boolean suspended;
    /** Whether the thinking widget was active when output got suspended: restart it on resume. */
    private boolean resumeThinking;
    /** Whether an interaction is pending a trailing newline to leave its card row on resume. */
    private boolean pendingInteractionSeparation;

    /**
     * Serializes every cursor-mutating terminal write between the stderr
     * spinner and the stdout interaction prompts so the two never interleave
     * on the shared physical line. Held for the full spinner frame/erase and
     * for each interaction card + prompt draw.
     */
    private final ReentrantLock renderLock = new ReentrantLock();

    public ReentrantLock renderLock() {
        return renderLock;
    }

    public AnsiShellFrontend(
            final MarkdownColorizer colorizer,
            final Symbols symbols,
            final ToolPresenter presenter,
            final CliVerbosity verbosity,
            final boolean tty,
            final boolean showThinking,
            final ToolOutputVisibility showToolOutput) {
        this.colorizer = colorizer;
        this.symbols = symbols;
        this.presenter = presenter;
        this.verbosity = verbosity;
        this.tty = tty;
        this.showThinking = showThinking;
        this.showToolOutput = showToolOutput;
        this.textBuffer = new StreamTextBuffer(colorizer::colorizeLine);
    }

    public TurnStats stats() {
        return stats;
    }

    public MarkdownColorizer colorizer() {
        return colorizer;
    }

    public Symbols symbols() {
        return symbols;
    }

    public boolean showThinking() {
        return showThinking;
    }

    public ToolOutputVisibility showToolOutput() {
        return showToolOutput;
    }

    @Override
    public void showThinking(final boolean value) {
        this.showThinking = value;
    }

    @Override
    public void showToolOutput(final ToolOutputVisibility value) {
        this.showToolOutput = value;
    }

    @Override
    public void status(final String message) {
        stopSpinner();
        ensureStderrNewline();
        printlnErr(MarkdownColorizer.status(symbols.infoMarker() + " " + message));
    }

    @Override
    public void notice(final AgentEvent.Notice.Level level, final String message) {
        stopSpinner();
        final var marker =
                switch (level) {
                    case ERROR -> symbols.errorMarker();
                    case WARN -> symbols.warningMarker();
                    case INFO -> symbols.infoMarker();
                };
        ensureStderrNewline();
        printlnErr(MarkdownColorizer.status(marker + " " + message));
    }

    @Override
    public void transcript(final String markdown) {
        // speaker-aware replay: thought and tool regions stay visibly subordinate
        // to assistant/user output the same way the live stream is dimmed
        final var sb = new StringBuilder();
        for (final var region : ConversationTranscript.regions(markdown)) {
            final var rendered = colorize(region.text());
            if (!colorizer.isEnabled()) {
                sb.append(rendered);
            } else if (region.thought()) {
                sb.append(MarkdownColorizer.thinkChunk(rendered));
            } else if (region.tool()) {
                sb.append(MarkdownColorizer.dimChunk(rendered));
            } else {
                sb.append(rendered);
            }
        }
        print(sb.toString());
    }

    @Override
    public void error(final String message) {
        stopSpinner();
        ensureStderrNewline();
        printlnErr(MarkdownColorizer.error("[error] " + message));
    }

    @Override
    public void activity(final String label) {
        beginSpinner(label);
    }

    @Override
    public void idle() {
        stopSpinner();
    }

    /**
     * Stops rendering events until {@link #resumeOutput()}: keeps interaction
     * boxes (questions, approvals) visible instead of having spinner frames
     * and thinking output bury them.
     */
    public void suspendOutput() {
        pendingLock.lock();
        try {
            suspended = true;
            resumeThinking = thinkingRoundOpen || spinnerRunning();
            pendingInteractionSeparation = true;
        } finally {
            pendingLock.unlock();
        }
        ensureStderrNewline();
        stopSpinner();
    }

    /** Resumes rendering and replays the events buffered while suspended. */
    public void resumeOutput() {
        final List<AgentEvent> queued;
        final boolean turnEnded;
        final boolean separate;
        pendingLock.lock();
        try {
            queued = List.copyOf(pendingEvents);
            pendingEvents.clear();
            suspended = false;
            turnEnded = queued.stream().anyMatch(AgentEvent.TurnDone.class::isInstance);
            separate = pendingInteractionSeparation;
            pendingInteractionSeparation = false;
        } finally {
            pendingLock.unlock();
        }
        if (tty && separate) {
            renderLock.lock();
            try {
                System.out.println();
                System.out.flush();
            } finally {
                renderLock.unlock();
            }
        }
        queued.forEach(this::onEvent);
        // Restart the thinking spinner only when the turn is still genuinely
        // active and no buffered event ended it: an unconditional restart here
        // would otherwise leave the spinner spinning forever over the freshly
        // drawn next prompt once the interaction finished.
        if (resumeThinking) {
            resumeThinking = false;
            if (!showThinking && !turnEnded && turnActive) {
                beginSpinner("thinking");
            }
        }
    }

    /** Renders a markdown block on stdout, colorizing each line when enabled. */
    private String colorize(final String markdown) {
        final var lines = markdown.split("\n", -1);
        final var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(colorizer.colorizeLine(lines[i]));
        }
        return sb.toString();
    }

    /**
     * Prepares a new turn: resets per-turn stats and the streaming buffer,
     * stops any leftover spinner and starts the "thinking" one.
     */
    @Override
    public void beginTurn() {
        stats.beginTurn();
        textBuffer.reset();
        thinkingRoundOpen = false;
        stderrNeedsNewline = false;
        stopSpinner();
        spinnerLock.lock();
        try {
            turnActive = true;
        } finally {
            spinnerLock.unlock();
        }
        if (!showThinking) {
            beginSpinner("thinking");
        }
    }

    /**
     * Finalizes a turn: stops the spinner, flushes the streamed answer buffer
     * to stdout and flushes both streams.
     */
    @Override
    public void endTurn() {
        // Flip the turn flag BEFORE stopping: the flag write and any racing
        // beginSpinner are serialized by spinnerLock, so a spinner started by
        // a late event thread either fails the turnActive check or is seen and
        // stopped by the stopSpinner call below. Stopping first (previous
        // order) let a spinner started in between spin forever past the turn.
        spinnerLock.lock();
        try {
            turnActive = false;
        } finally {
            spinnerLock.unlock();
        }
        stopSpinner();
        print(textBuffer.flush());
        System.out.flush();
        System.err.flush();
    }

    @Override
    public void onEvent(final AgentEvent event) {
        pendingLock.lock();
        try {
            if (suspended) {
                pendingEvents.add(event);
                return;
            }
        } finally {
            pendingLock.unlock();
        }
        final var prefix = subagentPrefix(event);
        if (event instanceof AgentEvent.TextDelta(String delta, String subagentId, String subagentName)) {
            stopSpinner();
            thinkingRoundOpen = false;
            print(textBuffer.append(prefix + delta));
        } else if (event instanceof AgentEvent.TextReset(String subagentId, String subagentName)) {
            textBuffer.reset();
            thinkingRoundOpen = false;
        } else if (event instanceof AgentEvent.ThinkingDelta(String delta, String subagentId, String subagentName)) {
            if (showThinking) {
                if (!thinkingRoundOpen) {
                    thinkingRoundOpen = true;
                    printlnErr(MarkdownColorizer.thinking(prefix + "thinking..."));
                }
                stopSpinner();
                printErr(MarkdownColorizer.thinking(prefix + delta));
                stderrNeedsNewline = !delta.endsWith("\n");
            } else {
                beginSpinner(prefix + "thinking");
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            stopSpinner();
            toolStreamed = false;
            thinkingRoundOpen = false;
            onToolStarted(t);
        } else if (event instanceof final AgentEvent.ToolStream t) {
            toolStreamed = true;
            if (showToolOutput.shown() && verbosity == CliVerbosity.RAW) {
                // streamed tool output replaces the spinner: frames and fragments
                // share the stderr line and would interleave, so output wins
                stopSpinner();
                printErr(prefix + (t.fragment().endsWith("\n") ? t.fragment() : t.fragment() + "\n"));
            } else if (showToolOutput.shown() && verbosity == CliVerbosity.VERBOSE) {
                stopSpinner();
                printErr(MarkdownColorizer.dim(prefix + t.fragment()));
            }
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            stats.incToolsRun();
            stopSpinner();
            if (showToolOutput.shown()) {
                onToolFinished(t);
            }
            if (!showThinking) {
                beginSpinner(prefix + "thinking");
            }
        } else if (event
                instanceof AgentEvent.Compacted(String kind, String mode, String subagentId, String subagentName)) {
            stopSpinner();
            ensureStderrNewline();
            printlnErr(MarkdownColorizer.status(prefix + symbols.infoMarker() + " context " + kind + " applied ("
                    + mode.toLowerCase(Locale.ROOT) + ")"));
        } else if (event
                instanceof
                AgentEvent.Notice(
                        var level,
                        String message,
                        var finishReason,
                        String subagentId,
                        String subagentName)) {
            notice(level, prefix + message);
        } else if (event instanceof AgentEvent.Status(String message, String subagentId, String subagentName)) {
            status(prefix + message);
        } else if (event instanceof AgentEvent.Error(String message, String subagentId, String subagentName)) {
            error(prefix + message);
        } else if (event instanceof final AgentEvent.UsageUpdated u) {
            stats.recordUsage(u);
        } else if (event instanceof final AgentEvent.TurnMetrics tm) {
            stats.recordTurnMetrics(tm);
        } else if (event instanceof AgentEvent.TurnDone) {
            // natural terminator for the current actor (main turn or async subagent/tool
            // worker): a hidden-thinking spinner left running by a late event would otherwise
            // keep spinning forever once the turn ended
            stopSpinner();
            ensureStderrNewline();
        }
    }

    private String subagentPrefix(final AgentEvent event) {
        String subagentId = null;
        String subagentName = null;
        if (event instanceof AgentEvent.TextDelta(var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.TextReset(String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ThinkingDelta(var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStarted(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStream(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolFinished(var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Compacted(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Notice(var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Status(var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Error(var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof AgentEvent.TurnMetrics(var _, var _, var _, var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.TurnDone(var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolTimingEvent(var _, var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        }
        if (subagentId != null) {
            final var displayName =
                    subagentName != null ? subagentName : subagentId.substring(0, Math.min(8, subagentId.length()));
            return "[" + displayName + "] ";
        }
        return "";
    }

    private void onToolStarted(final AgentEvent.ToolStarted t) {
        if (!showToolOutput.shown() && !showToolOutput.collapsed()) {
            return;
        }
        final var collapsed = showToolOutput.collapsed();
        final var prefix = subagentPrefix(t);
        if (verbosity == CliVerbosity.RAW) {
            if (!collapsed) {
                ensureStderrNewline();
                printlnErr(MarkdownColorizer.status(prefix + "[tool] " + t.name() + " " + t.args()));
            }
            return;
        }
        // the whole card is one render unit: frames skipped on tryLock cannot
        // draw between its lines once the spinner was stopped by the caller
        renderLock.lock();
        try {
            ensureStderrNewline();
            System.err.println(
                    MarkdownColorizer.bold(prefix + symbols.toolMarker() + presenter.summarize(t.name(), t.args())));
        } finally {
            renderLock.unlock();
        }
        if (verbosity == CliVerbosity.COMPACT && !collapsed && t.name().equals("bash")) {
            beginSpinner(prefix + "running " + presenter.summarize(t.name(), t.args()));
        }
    }

    private void onToolFinished(final AgentEvent.ToolFinished t) {
        final var prefix = subagentPrefix(t);
        final var indent = "  ";
        renderLock.lock();
        try {
            ensureStderrNewline();
            if (verbosity == CliVerbosity.RAW) {
                if (!t.success() && t.output() != null && !t.output().isBlank()) {
                    System.err.println(MarkdownColorizer.error(prefix + t.output()));
                }
                return;
            }
            final var result = presenter.result(t.name(), t.output(), t.success());
            System.err.println(MarkdownColorizer.dim(prefix + indent + symbols.resultPrefix())
                    + (t.success() ? MarkdownColorizer.success(result) : MarkdownColorizer.error(result)));
            if (verbosity == CliVerbosity.VERBOSE) {
                // show the full result for tools that did not stream their output live (e.g. bash streams,
                // most other tools only surface their output through ToolFinished)
                if (!toolStreamed && t.output() != null && !t.output().isBlank()) {
                    for (final var line : t.output().stripTrailing().split("\n")) {
                        System.err.println(MarkdownColorizer.dim(prefix + indent + line));
                    }
                }
            }
            if (verbosity == CliVerbosity.COMPACT) {
                for (final var line : presenter.diff(t.name(), null, DIFF_MAX_LINES)) {
                    System.err.println(prefix + indent + colorizeDiff(line));
                }
            }
        } finally {
            renderLock.unlock();
        }
    }

    private String colorizeDiff(final String line) {
        final var marker = line.startsWith(symbols.diffDel())
                ? symbols.diffDel()
                : line.startsWith(symbols.diffAdd()) ? symbols.diffAdd() : null;
        if (marker == null) {
            return MarkdownColorizer.dim(line);
        }
        final var rest = line.substring(marker.length());
        return (marker.equals(symbols.diffDel())
                ? MarkdownColorizer.diffDel(marker + rest)
                : MarkdownColorizer.diffAdd(marker + rest));
    }

    /**
     * Colored, current-state rendering of the todo list: green check for
     * completed, yellow arrow for in-progress, dim dot for pending, plus a
     * done/total progress line.
     */
    public List<String> renderTodos(final List<TodoItem> todos) {
        final var out = new ArrayList<String>();
        if (todos.isEmpty()) {
            out.add(MarkdownColorizer.status("(no todos)"));
            return out;
        }
        var done = 0;
        for (final var item : todos) {
            switch (item.status() == null ? "" : item.status()) {
                case "in_progress" -> out.add(MarkdownColorizer.warning(symbols.todoInProgress() + item.content()));
                case "completed" -> {
                    out.add(MarkdownColorizer.diffAdd(symbols.todoCompleted() + item.content()));
                    done++;
                }
                default -> out.add(MarkdownColorizer.dim(symbols.todoPending() + item.content()));
            }
        }
        out.add(MarkdownColorizer.status(done + "/" + todos.size() + " done"));
        return out;
    }

    /**
     * Whether a spinner is currently armed, read under {@link #spinnerLock}
     * so callers observe a consistent lifecycle state.
     */
    boolean spinnerRunning() {
        spinnerLock.lock();
        try {
            return spinner != null;
        } finally {
            spinnerLock.unlock();
        }
    }

    /**
     * Starts the spinner when none is running; a thinking stream can call this
     * once per token, so reusing the active spinner avoids leaking one thread
     * (and one elapsed timer) per delta. Atomic with {@link #stopSpinner()}
     * and the {@code turnActive} flips so a spinner can never be armed after
     * the turn ended.
     */
    private void beginSpinner(final String label) {
        if (!tty) {
            return;
        }
        spinnerLock.lock();
        try {
            if (!turnActive || spinner != null) {
                return;
            }
            spinner = new Spinner(symbols, System.err, tty, renderLock);
            spinner.start(label);
        } finally {
            spinnerLock.unlock();
        }
    }

    private void stopSpinner() {
        final Spinner current;
        spinnerLock.lock();
        try {
            current = spinner;
            spinner = null;
        } finally {
            spinnerLock.unlock();
        }
        if (current != null) {
            // join + erase outside spinnerLock: stop() waits for the animation
            // worker, whose frames need renderLock - never nest the two locks
            current.stop();
        }
    }

    /**
     * Thoughts stream continuously without a trailing newline; before printing a
     * discrete line on stderr, terminate the open thought line so it is not glued
     * to the following tool/status line.
     */
    private void ensureStderrNewline() {
        if (stderrNeedsNewline) {
            printlnErr("");
            stderrNeedsNewline = false;
        }
    }

    /** Renders on stdout under {@link #renderLock}: spinner frames are dropped for the whole write. */
    private void print(final String text) {
        renderLock.lock();
        try {
            System.out.print(text);
            System.out.flush();
        } finally {
            renderLock.unlock();
        }
    }

    /** Renders on stderr under {@link #renderLock}: spinner frames are dropped for the whole write. */
    private void printErr(final String text) {
        renderLock.lock();
        try {
            System.err.print(text);
            System.err.flush();
        } finally {
            renderLock.unlock();
        }
    }

    /** Renders a line on stderr under {@link #renderLock}: spinner frames are dropped for the whole write. */
    private void printlnErr(final String text) {
        renderLock.lock();
        try {
            System.err.println(text);
            System.err.flush();
        } finally {
            renderLock.unlock();
        }
    }
}
