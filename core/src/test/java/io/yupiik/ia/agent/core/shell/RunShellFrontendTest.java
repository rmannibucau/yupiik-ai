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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ExtendWith(TestJsonMapper.class)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock("java.util.logging")
class RunShellFrontendTest {
    @Test
    void responseMarkersUseCapturedJulTimeWithoutChangingTheStdoutProduct() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.HIDDEN, presenter);
        frontend.onEvent(new AgentEvent.FeedbackStarted("text", 1000, "s1", null, null));
        frontend.onEvent(new AgentEvent.TextDelta("exact", "s1"));
        frontend.onEvent(new AgentEvent.TextDelta("\nproduct", "s1"));
        frontend.onEvent(new AgentEvent.FeedbackStarted("thinking", 2000, "s1", null, null));
        frontend.onEvent(new AgentEvent.FeedbackStarted("text", 3000, "s1", null, null));
        frontend.onEvent(new AgentEvent.TextDelta("!", "s1"));
        assertEquals("exact\nproduct!", out.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("INFO text response started", "INFO text response started"), logs);
        assertEquals(List.of(1000L, 3000L), logTimes);
    }

    private ToolPresenter presenter;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> logs = new ArrayList<>();
    private final List<Long> logTimes = new ArrayList<>();
    private Handler handler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    void capture(final JsonMapper mapper) {
        presenter = new ToolPresenter(mapper);
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logs.add(record.getLevel() + " " + record.getMessage());
                logTimes.add(record.getInstant().toEpochMilli());
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
        final var logger = Logger.getLogger(RunShellFrontend.class.getName());
        logger.addHandler(handler);
        // isolate the JVM-wide JUL logger for the duration of the test: classes run in parallel
        // (same fork) and a parent handler's level/filter could swallow the records before they
        // reach this handler, or leave the logger level changed for the following tests
        previousLevel = logger.getLevel();
        previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        final var logger = Logger.getLogger(RunShellFrontend.class.getName());
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
        logger.setUseParentHandlers(previousUseParentHandlers);
    }

    @Test
    void answerStreamsOnStdoutAndEventsAreLogged() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.COLLAPSED, presenter);

        frontend.onEvent(new AgentEvent.TextDelta("hel", "s1"));
        frontend.onEvent(new AgentEvent.TextDelta("lo", "s1"));
        frontend.onEvent(new AgentEvent.ToolStarted("read", "{}", "s1"));
        frontend.onEvent(new AgentEvent.ToolFinished("read", "out", true, "s1"));
        frontend.onEvent(new AgentEvent.Status("working", "s1"));
        frontend.onEvent(new AgentEvent.Error("boom", "s1"));

        assertEquals("hello", out.toString(StandardCharsets.UTF_8));
        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("read started"), joined);
        assertTrue(joined.contains("INFO working"), joined);
        assertTrue(joined.contains("SEVERE boom"), joined);
        assertFalse(joined.contains("[read] out"), "tool output must not print when showToolOutput is off");
    }

    @Test
    void toolOutputPrintsOnceWhenNotStreamedAndEnabled() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.SHOWN, presenter);

        frontend.onEvent(new AgentEvent.ToolFinished("read", "line1\nline2", true, "s1"));

        assertEquals(2, logs.size());
        assertTrue(logs.get(1).contains("line1\nline2"), logs.toString());
    }

    @Test
    void streamedToolOutputIsNotReprinted() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.SHOWN, presenter);

        frontend.onEvent(new AgentEvent.ToolStream("bash", "streamed line", "s1"));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "streamed line", true, "s1"));

        assertEquals(2, logs.size());
        assertTrue(logs.get(0).contains("streamed line"), logs.get(0));
    }

    @Test
    void failuresAndNoticesUseTheRightLevels() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.COLLAPSED, presenter);

        frontend.onEvent(new AgentEvent.ToolFinished("bash", "oops", false, "s1"));
        frontend.onEvent(AgentEvent.Notice.warn("careful", "s1"));

        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("WARNING bash failed"), joined);
        assertTrue(joined.contains("WARNING careful"), joined);
    }

    @Test
    void thinkingStreamStaysRawOnStderrWhenEnabled() {
        final var frontend = new RunShellFrontend(true, ToolOutputVisibility.COLLAPSED, presenter);

        frontend.onEvent(new AgentEvent.ThinkingDelta("thinking...", "s1"));

        assertEquals("thinking...", err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Regression test for garbled reasoning: a spawned sub-agent streams on its own
     * thread while the parent turn keeps going, and thought deltas carry no trailing
     * newline. The open stderr line is therefore owned by one actor at a time.
     */
    @Test
    void concurrentThoughtsNeverShareALine() throws InterruptedException {
        final var frontend = new RunShellFrontend(true, ToolOutputVisibility.COLLAPSED, presenter);
        for (int i = 0; i < 200; i++) {
            final var barrier = new CyclicBarrier(2);
            final var parent = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                } catch (final Exception e) {
                    return;
                }
                for (int j = 0; j < 20; j++) {
                    frontend.onEvent(new AgentEvent.ThinkingDelta("P" + j + " ", "s1"));
                }
            });
            final var child = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                } catch (final Exception e) {
                    return;
                }
                for (int j = 0; j < 20; j++) {
                    frontend.onEvent(new AgentEvent.ThinkingDelta("C" + j + " ", "s1", "sub-1", "kid"));
                }
            });
            parent.join();
            child.join();
        }

        final var lines = err.toString(StandardCharsets.UTF_8).split("\n", -1);
        assertTrue(lines.length > 2, "the race produced no reasoning to check");
        for (final var line : lines) {
            // P*/C* are the per-actor delta tokens, one line may only carry one voice
            final var parent2 = line.matches(".*P\\d+ .*");
            final var child2 = line.matches(".*C\\d+ .*");
            assertFalse(parent2 && child2, () -> "two thoughts spliced into one line: \"" + line + "\"");
        }
    }

    @Test
    void hiddenAndCollapsedRespectSuccessfulStreamsAndFinalVisibility() {
        for (final var visibility : List.of(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.COLLAPSED)) {
            logs.clear();
            final var frontend = new RunShellFrontend(false, visibility, presenter);
            frontend.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"secret request\"}", 9, "s", null, null));
            frontend.onEvent(new AgentEvent.ToolStream("bash", "secret output\n", 9, "s", null, null));
            frontend.onEvent(new AgentEvent.ToolFinished("bash", "secret result", true, false, 9, "s", null, null));
            final var joined = String.join("\n", logs);
            assertFalse(joined.contains("secret"), joined);
            if (visibility == ToolOutputVisibility.HIDDEN) {
                assertTrue(logs.isEmpty(), joined);
            } else {
                assertTrue(joined.contains("#9 bash done"), joined);
            }
        }
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void failuresRemainDiagnosticRegardlessOfVisibilityButPendingIsNotFailure() {
        for (final var visibility : ToolOutputVisibility.values()) {
            logs.clear();
            final var frontend = new RunShellFrontend(false, visibility, presenter);
            frontend.onEvent(
                    new AgentEvent.ToolStarted("bash", "{\"command\":\"private command\"}", 9, "s", null, null));
            frontend.onEvent(new AgentEvent.ToolStream("bash", "error body\n", 9, "s", null, null));
            frontend.onEvent(new AgentEvent.ToolFinished(
                    "bash", "error body\nlast diagnostic", false, false, 9, "s", null, null));
            final var joined = String.join("\n", logs);
            assertTrue(joined.contains("WARNING #9 bash failed"), joined);
            assertTrue(joined.contains("error body"), joined);
            assertTrue(joined.contains("last diagnostic"), joined);
            if (!visibility.shown()) {
                assertFalse(joined.contains("private command"), joined);
            }
            logs.clear();
            frontend.onEvent(new AgentEvent.ToolFinished(
                    "bash", "pending body\nExit code: 7", false, true, 10, "s", null, null));
            assertFalse(logs.stream().anyMatch(it -> it.startsWith("WARNING")), logs.toString());
            if (visibility == ToolOutputVisibility.HIDDEN) {
                assertTrue(logs.isEmpty(), logs.toString());
            }
        }
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void collapsedActivityKeepsRequestSummaryAndNonzeroExitIsSeparate() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.COLLAPSED, presenter);
        frontend.onEvent(new AgentEvent.ToolStarted("read", "{\"path\":\"pom.xml\"}", 3, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "output\nExit code: 7", true, false, 4, "s", null, null));
        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("#3 read started (pom.xml)"), joined);
        assertTrue(joined.contains("INFO #4 bash done"), joined);
        assertTrue(joined.contains("WARNING #4 bash execution exit code: 7"), joined);
        assertFalse(joined.contains("bash failed"), joined);
    }

    @Test
    void perInvocationStreamsKeepWhitespaceAndDoNotDropFinalDiagnostics() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.SHOWN, presenter);
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", 1, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", 2, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", 1, "s", "kid", "worker"));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "  same", 1, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "  \n\tend", 1, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "child", 1, "s", "kid", "worker"));
        frontend.onEvent(
                new AgentEvent.ToolFinished("bash", "  same  \n\tend\nDIAGNOSTIC", false, false, 1, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "second output", true, false, 2, "s", null, null));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "child", false, true, 1, "s", "kid", "worker"));
        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("output:   same  "), joined);
        assertTrue(joined.contains("output: \tend"), joined);
        assertTrue(joined.contains("DIAGNOSTIC"), joined);
        assertTrue(joined.contains("#2 bash result:\nsecond output"), joined);
        assertTrue(joined.contains("INFO [worker] #1 bash pending"), joined);
        assertFalse(joined.contains("WARNING [worker]"), joined);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void legacyFallbackIsPerNameAndActorAndRemainderFlushesOnEndTurn() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.SHOWN, presenter);
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s"));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "trailing  ", "s"));
        frontend.onEvent(new AgentEvent.ToolStarted("read", "{}", "s"));
        frontend.onEvent(new AgentEvent.ToolFinished("read", "read result", true, "s"));
        frontend.endTurn();
        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("read result"), joined);
        assertTrue(joined.contains("output: trailing  "), joined);
    }
}
