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

import io.yupiik.ia.agent.core.agent.AgentEvent;
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
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock("java.util.logging")
class RunShellFrontendTest {
    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> logs = new ArrayList<>();
    private Handler handler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logs.add(record.getLevel() + " " + record.getMessage());
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
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.COLLAPSED);

        frontend.onEvent(new AgentEvent.TextDelta("hel", "s1"));
        frontend.onEvent(new AgentEvent.TextDelta("lo", "s1"));
        frontend.onEvent(new AgentEvent.ToolStarted("read_file", "{}", "s1"));
        frontend.onEvent(new AgentEvent.ToolFinished("read_file", "out", true, "s1"));
        frontend.onEvent(new AgentEvent.Status("working", "s1"));
        frontend.onEvent(new AgentEvent.Error("boom", "s1"));

        assertEquals("hello", out.toString(StandardCharsets.UTF_8));
        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("tool started: read_file"), joined);
        assertTrue(joined.contains("INFO working"), joined);
        assertTrue(joined.contains("SEVERE boom"), joined);
        assertFalse(joined.contains("[read_file] out"), "tool output must not print when showToolOutput is off");
    }

    @Test
    void toolOutputPrintsOnceWhenNotStreamedAndEnabled() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.SHOWN);

        frontend.onEvent(new AgentEvent.ToolFinished("read_file", "line1\nline2", true, "s1"));

        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("line1\nline2"), logs.get(0));
    }

    @Test
    void streamedToolOutputIsNotReprinted() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.SHOWN);

        frontend.onEvent(new AgentEvent.ToolStream("bash", "streamed line", "s1"));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "streamed line", true, "s1"));

        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("streamed line"), logs.get(0));
    }

    @Test
    void failuresAndNoticesUseTheRightLevels() {
        final var frontend = new RunShellFrontend(false, ToolOutputVisibility.COLLAPSED);

        frontend.onEvent(new AgentEvent.ToolFinished("bash", "oops", false, "s1"));
        frontend.onEvent(AgentEvent.Notice.warn("careful", "s1"));

        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("WARNING tool failed: bash"), joined);
        assertTrue(joined.contains("WARNING careful"), joined);
    }

    @Test
    void thinkingStreamStaysRawOnStderrWhenEnabled() {
        final var frontend = new RunShellFrontend(true, ToolOutputVisibility.COLLAPSED);

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
        final var frontend = new RunShellFrontend(true, ToolOutputVisibility.COLLAPSED);
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
}
