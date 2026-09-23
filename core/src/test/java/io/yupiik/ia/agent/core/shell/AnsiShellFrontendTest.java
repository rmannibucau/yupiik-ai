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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.CliVerbosity;
import io.yupiik.ia.agent.core.cli.MarkdownColorizer;
import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class AnsiShellFrontendTest {
    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private JsonMapperImpl jsonMapper;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        if (jsonMapper != null) {
            jsonMapper.close();
            jsonMapper = null;
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private AnsiShellFrontend frontend(final boolean showThinking, final boolean showToolOutput) {
        return frontend(false, showThinking, ToolOutputVisibility.fromBoolean(showToolOutput));
    }

    private AnsiShellFrontend frontend(
            final boolean tty, final boolean showThinking, final ToolOutputVisibility showToolOutput) {
        return frontend(new MarkdownColorizer(false), tty, showThinking, showToolOutput);
    }

    private AnsiShellFrontend frontend(final boolean tty, final boolean showThinking, final boolean showToolOutput) {
        return frontend(
                new MarkdownColorizer(false), tty, showThinking, ToolOutputVisibility.fromBoolean(showToolOutput));
    }

    private AnsiShellFrontend frontend(
            final MarkdownColorizer colorizer,
            final boolean tty,
            final boolean showThinking,
            final ToolOutputVisibility showToolOutput) {
        if (jsonMapper == null) {
            jsonMapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
        }
        return new AnsiShellFrontend(
                colorizer,
                new Symbols(false),
                new ToolPresenter(jsonMapper),
                CliVerbosity.COMPACT,
                tty,
                showThinking,
                showToolOutput);
    }

    @Test
    void suspensionBuffersEventsAndResumeReplaysThem() {
        final var frontend = frontend(false, true);
        final var interrupt = new AgentEvent.Status("mid-gate status");

        frontend.suspendOutput();
        frontend.onEvent(new AgentEvent.ThinkingDelta("hidden"));
        frontend.onEvent(interrupt);
        assertEquals("", out.toString());
        assertEquals("", err.toString());

        frontend.resumeOutput();
        assertTrue(out.toString().isEmpty());
        assertTrue(err.toString().contains("mid-gate status"));

        frontend.onEvent(new AgentEvent.Status("after"));
        assertTrue(err.toString().contains("after"));
    }

    @Test
    void suspendTerminatesOpenThoughtLineBeforeCard() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        // a thought delta with no trailing newline leaves an open line
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("reasoning"));

        // suspending for an interaction must terminate that line so the stdout card
        // is never drawn onto the same terminal row
        frontend.suspendOutput();
        assertTrue(
                err.toString(StandardCharsets.UTF_8).endsWith("\n"),
                "open thought line must terminate on suspend: " + err);

        // events buffered while suspended stay silent until resume
        frontend.onEvent(new AgentEvent.ThinkingDelta("hidden"));
        assertEquals("", out.toString(StandardCharsets.UTF_8));

        frontend.resumeOutput();
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("hidden"));
    }

    @Test
    void interactionResumeSeparatesCardAndRestartsThinking() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // nested/sequential resumes of one interaction must emit a single separation
        // newline so the thinking line cannot overwrite the card's last row
        frontend.suspendOutput();
        assertFalse(frontend.spinnerRunning());
        System.out.print("answer> "); // card's last prompt row, no trailing newline
        frontend.resumeOutput(); // separation newline + thinking restart
        assertTrue(frontend.spinnerRunning());
        assertEquals(1, newlines(out.toString(StandardCharsets.UTF_8)));
        frontend.resumeOutput(); // deduplicated: no second newline
        assertEquals(1, newlines(out.toString(StandardCharsets.UTF_8)));
        assertTrue(frontend.spinnerRunning());

        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void resumeDoesNotRestartThinkingAfterBufferedTurnDone() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // the interaction suspends output; while blocked the turn completes and its
        // TurnDone is buffered. On resume the buffered TurnDone stops the spinner,
        // and resumeOutput must NOT re-arm it (otherwise it would spin forever over
        // the freshly drawn prompt once the answer is ready).
        frontend.suspendOutput();
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.TurnDone("answer"));
        frontend.resumeOutput();
        assertFalse(frontend.spinnerRunning(), "spinner must stay off after a buffered TurnDone");

        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    private static long newlines(final String text) {
        return text.codePoints().filter(c -> c == '\n').count();
    }

    @Test
    void streamsAnswerToStdoutAndCountsTools() {
        final var frontend = frontend(false, false);

        frontend.onEvent(new AgentEvent.TextDelta("hel"));
        frontend.onEvent(new AgentEvent.TextDelta("lo"));
        frontend.onEvent(new AgentEvent.ToolStarted("read_file", "{}"));
        frontend.onEvent(new AgentEvent.ToolFinished("read_file", "out", true));
        frontend.onEvent(new AgentEvent.TurnDone("hello"));
        frontend.endTurn();

        assertEquals("hello\n", out.toString(StandardCharsets.UTF_8));
        assertEquals(1, frontend.stats().toolsRun());
    }

    @Test
    void hiddenThinkingTriggersActivityOnly() {
        final var frontend = frontend(false, false);

        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning"));
        frontend.onEvent(new AgentEvent.UsageUpdated(null, 100, 5.0));

        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
        assertEquals(100, frontend.stats().lastUsage().estimatedContextTokens());
    }

    @Test
    void statusNoticeAndErrorGoToStderr() {
        final var frontend = frontend(false, false);

        frontend.status("busy");
        frontend.notice(AgentEvent.Notice.Level.WARN, "watch out");
        frontend.error("boom");

        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("busy"), stderr);
        assertTrue(stderr.contains("watch out"), stderr);
        assertTrue(stderr.contains("boom"), stderr);
    }

    @Test
    void togglesAreReflected() {
        final var frontend = frontend(true, true);

        assertTrue(frontend.showThinking());
        assertEquals(ToolOutputVisibility.SHOWN, frontend.showToolOutput());
        frontend.showThinking(false);
        frontend.showToolOutput(ToolOutputVisibility.COLLAPSED);
        assertFalse(frontend.showThinking());
        assertEquals(ToolOutputVisibility.COLLAPSED, frontend.showToolOutput());
    }

    @Test
    void renderTodosMarksStates() {
        final var frontend = frontend(false, false);

        final var lines = frontend.renderTodos(List.of(
                new io.yupiik.ia.agent.core.todo.TodoItem("write", "completed"),
                new io.yupiik.ia.agent.core.todo.TodoItem("test", "in_progress"),
                new io.yupiik.ia.agent.core.todo.TodoItem("ship", "pending")));

        assertEquals(4, lines.size());
        assertTrue(lines.get(3).contains("1/3 done"));
        assertTrue(lines.get(0).contains("write"));
    }

    @Test
    void shownThinkingPrintsDimAndNeverSpins() {
        final var frontend = frontend(true, true, true);
        frontend.beginTurn();
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning"));
        assertTrue(err.toString().contains("reasoning"));
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "ls"));
        assertTrue(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "out", true));
        assertFalse(frontend.spinnerRunning());
        frontend.endTurn();
    }

    @Test
    void hiddenThinkingSpinsDuringTurn() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void lateAsyncEventsAfterEndTurnCannotRestartSpinner() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // the turn finishes and stops the spinner...
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());

        // ...then a background subagent (emitting on the shared bus) sends its own
        // thinking/tool deltas and finally its completion; none of those must re-arm
        // the spinner now that the turn is over, otherwise it would spin forever
        frontend.onEvent(new AgentEvent.ThinkingDelta("subagent pondering", "sub-1", "researcher"));
        assertFalse(frontend.spinnerRunning(), "late thinking delta must not restart the spinner");
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "ls", "sub-1", "researcher"));
        assertFalse(frontend.spinnerRunning(), "late tool start must not restart the spinner");
        frontend.onEvent(new AgentEvent.ThinkingDelta("subagent ponder again", "sub-1", "researcher"));
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.TurnDone("subagent answer", "sub-1", "researcher"));
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void turnDoneStopsSpinnerEvenBeforeEndTurn() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // the actor completes (main answer streamed, then TurnDone): the spinner must
        // already be gone before endTurn, so no dangling spin is ever visible at the prompt
        frontend.onEvent(new AgentEvent.TurnDone("answer"));
        assertFalse(frontend.spinnerRunning());
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void thoughtWithoutNewlineIsTerminatedBeforeToolLine() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        // a thought delta that does not end with a newline
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning"));
        // a following tool line must not be glued onto the thought line
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "ls"));

        final var stderr = err.toString(StandardCharsets.UTF_8);
        // the thought delta is ANSI-wrapped (\u001b[38;5;240m...\u001b[0m); the guard must
        // insert a newline right after it so the tool line is not glued onto the thought line
        assertTrue(
                stderr.contains("reasoning\u001b[0m\n"),
                "thought must terminate with a newline before the tool line: " + stderr);
    }

    @Test
    void replayedTranscriptDimsThoughtAndToolRegions() {
        final var frontend = frontend(new MarkdownColorizer(true), false, true, ToolOutputVisibility.SHOWN);
        frontend.transcript(ConversationTranscript.THOUGHT_BEGIN + "\n**Thinking**\n\nsecret\n"
                + ConversationTranscript.THOUGHT_END
                + "\nplain answer\n\n"
                + ConversationTranscript.TOOL_BEGIN + "\n```text\nout\n```\n"
                + ConversationTranscript.TOOL_END);
        final var stdout = out.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.startsWith("\u001b[38;5;240m"), "thought region must lead in thinking grey: " + stdout);
        assertTrue(stdout.contains("plain answer"), stdout);
        assertTrue(stdout.contains("\u001b[2m"), "tool region must be dimmed: " + stdout);
    }
}
