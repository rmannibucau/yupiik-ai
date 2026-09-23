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
