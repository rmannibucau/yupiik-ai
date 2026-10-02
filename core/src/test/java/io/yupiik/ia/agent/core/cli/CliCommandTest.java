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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ExtendWith(TestJsonMapper.class)
class CliCommandTest {
    private java.io.PrintStream originalOut;
    private java.io.PrintStream originalErr;
    private final java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new java.io.PrintStream(
                java.io.ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void verbosePrintsFullOutputForNonStreamedTool(final JsonMapper mapper) throws Exception {
        final var dispatcher = dispatcher("verbose", ToolOutputVisibility.SHOWN, mapper);

        dispatcher.accept(new AgentEvent.ToolStarted("read_file", "{}", "s1"));
        dispatcher.accept(new AgentEvent.ToolFinished("read_file", "line1\nline2", true, "s1"));

        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("line1"), "verbose should show the full non-streamed output:\n" + stderr);
        assertTrue(stderr.contains("line2"));
    }

    @Test
    void verboseDoesNotReprintStreamedToolOutput(final JsonMapper mapper) throws Exception {
        final var dispatcher = dispatcher("verbose", ToolOutputVisibility.SHOWN, mapper);

        dispatcher.accept(new AgentEvent.ToolStarted("bash", "{}", "s1"));
        dispatcher.accept(new AgentEvent.ToolStream("bash", "streamed line", "s1"));
        dispatcher.accept(new AgentEvent.ToolFinished("bash", "streamed line", true, "s1"));

        final var stderr = err.toString(StandardCharsets.UTF_8);
        // streamed output must be printed live but not re-printed from ToolFinished.output
        assertTrue(stderr.contains("streamed line"));
        assertFalse(
                stderr.lines()
                                .filter(l -> !l.isBlank() && l.contains("streamed line"))
                                .count()
                        > 2,
                "streamed output should not be duplicated:\n" + stderr);
    }

    private static java.util.function.Consumer<AgentEvent> dispatcher(
            final String presentation, final ToolOutputVisibility showToolOutput, final JsonMapper mapper) {
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.parse(presentation),
                false,
                false,
                showToolOutput);
        return frontend::onEvent;
    }
}
