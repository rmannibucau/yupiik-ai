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
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        Logger.getLogger(RunShellFrontend.class.getName()).removeHandler(handler);
    }

    @Test
    void answerStreamsOnStdoutAndEventsAreLogged() {
        final var frontend = new RunShellFrontend(false, false);

        frontend.onEvent(new AgentEvent.TextDelta("hel"));
        frontend.onEvent(new AgentEvent.TextDelta("lo"));
        frontend.onEvent(new AgentEvent.ToolStarted("read_file", "{}"));
        frontend.onEvent(new AgentEvent.ToolFinished("read_file", "out", true));
        frontend.onEvent(new AgentEvent.Status("working"));
        frontend.onEvent(new AgentEvent.Error("boom"));

        assertEquals("hello", out.toString(StandardCharsets.UTF_8));
        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("tool started: read_file"), joined);
        assertTrue(joined.contains("INFO working"), joined);
        assertTrue(joined.contains("SEVERE boom"), joined);
        assertFalse(joined.contains("[read_file] out"), "tool output must not print when showToolOutput is off");
    }

    @Test
    void toolOutputPrintsOnceWhenNotStreamedAndEnabled() {
        final var frontend = new RunShellFrontend(false, true);

        frontend.onEvent(new AgentEvent.ToolFinished("read_file", "line1\nline2", true));

        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("line1\nline2"), logs.get(0));
    }

    @Test
    void streamedToolOutputIsNotReprinted() {
        final var frontend = new RunShellFrontend(false, true);

        frontend.onEvent(new AgentEvent.ToolStream("bash", "streamed line"));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "streamed line", true));

        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("streamed line"), logs.get(0));
    }

    @Test
    void failuresAndNoticesUseTheRightLevels() {
        final var frontend = new RunShellFrontend(false, false);

        frontend.onEvent(new AgentEvent.ToolFinished("bash", "oops", false));
        frontend.onEvent(AgentEvent.Notice.warn("careful"));

        final var joined = String.join("\n", logs);
        assertTrue(joined.contains("WARNING tool failed: bash"), joined);
        assertTrue(joined.contains("WARNING careful"), joined);
    }

    @Test
    void thinkingStreamStaysRawOnStderrWhenEnabled() {
        final var frontend = new RunShellFrontend(true, false);

        frontend.onEvent(new AgentEvent.ThinkingDelta("thinking..."));

        assertEquals("thinking...", err.toString(StandardCharsets.UTF_8));
    }
}
