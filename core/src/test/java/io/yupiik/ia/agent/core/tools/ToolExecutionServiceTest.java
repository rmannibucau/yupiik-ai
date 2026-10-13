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
package io.yupiik.ia.agent.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.optimize.GenericOutputProcessor;
import io.yupiik.ia.agent.core.optimize.MavenOutputProcessor;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolExecutionServiceTest {
    @TempDir
    Path storageHome;

    /** The status reporter is per-session: it now travels in the tool context, not the service. */
    private ToolContext context(final List<String> reports) {
        final var reporter = new StatusReporter();
        reporter.setSink(reports::add);
        return new ToolContext("s1", null, null, null, null, null, reporter, null, null, Cancellations.NEVER);
    }

    private ToolExecutionService service() {
        final var configuration = config("10");
        return new ToolExecutionService(
                configuration,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                List.of(new GenericOutputProcessor(configuration)));
    }

    private CodingAgentConfiguration config(final String genericMaxLines) {
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                Map.of("yca.storageHome", storageHome.toString(), "yca.optimize.genericMaxLines", genericMaxLines));
    }

    private EmbeddedTool tool(final String name, final String output) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "fake " + name;
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(final Map<String, Object> args) {
                return ToolResult.ok(output);
            }
        };
    }

    @Test
    void nonBashToolsAreNotReprocessed() {
        final var output = "line\n".repeat(50);
        final var reports = new ArrayList<String>();
        final var result = service().execute(tool("read_file", output), Map.of(), context(reports));
        assertEquals(output, result.output(), "read_file output must not be re-truncated by processors");
        assertTrue(reports.isEmpty(), reports.toString());
    }

    @Test
    void bashTinyReductionIsIgnored() {
        // 1 trailing whitespace char trimmed over a 100-char line: ~1% reduction, below the 10% floor
        final var output = "x".repeat(100) + " ";
        final var reports = new ArrayList<String>();
        final var result = service().execute(tool("bash", output), Map.of("command", "cat big.txt"), context(reports));
        assertEquals(output, result.output(), "sub-threshold reductions must keep the original output");
        assertTrue(reports.isEmpty(), reports.toString());
    }

    @Test
    void bashMeaningfulReductionIsApplied() {
        final var sb = new StringBuilder();
        for (var i = 0; i < 100; i++) {
            sb.append("kept line ").append(i).append('\n');
            sb.append("   \n");
        }
        final var reports = new ArrayList<String>();
        final var result =
                service().execute(tool("bash", sb.toString()), Map.of("command", "cat big.txt"), context(reports));
        assertTrue(result.output().length() < sb.length() / 2, result.output());
        assertTrue(result.output().contains("kept line 0"), result.output());
        assertFalse(result.output().contains("   \n"), "blank runs must be compacted");
        assertEquals(1, reports.size(), reports.toString());
        assertTrue(reports.get(0).contains("output optimized by Generic"), reports.toString());
    }

    @Test
    void perToolDisabledProcessorBehavesAsIdentity() {
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                Map.of("yca.storageHome", storageHome.toString(), "yca.optimize.toolOutputs.generic.enabled", "false"));
        final var service = new ToolExecutionService(
                configuration,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                List.of(new GenericOutputProcessor(configuration)));
        final var output = "kept line 0\nkept line 1\n".repeat(50);
        final var reports = new ArrayList<String>();
        final var result = service.execute(tool("bash", output), Map.of("command", "cat big.txt"), context(reports));
        assertEquals(output, result.output(), "disabled processor must leave the output byte-identical");
        assertTrue(reports.isEmpty(), reports.toString());
    }

    @Test
    void globalDisabledStillWins() {
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.optimize.toolOutputs.generic.enabled", "true",
                "yca.optimize.disabled", "generic"));
        final var service = new ToolExecutionService(
                configuration,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                List.of(new GenericOutputProcessor(configuration)));
        final var output = "line\n".repeat(50);
        final var reports = new ArrayList<String>();
        final var result = service.execute(tool("bash", output), Map.of("command", "cat big.txt"), context(reports));
        assertEquals(output, result.output());
        assertTrue(reports.isEmpty());
    }

    @Test
    void commandWrapperIsUnwrappedBeforeProcessorDispatch() {
        // a `sudo timeout 120 mvn ...` command must engage the maven processor (head unwrapped to `mvn`)
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                Map.of("yca.storageHome", storageHome.toString()));
        final var service = serviceWith(configuration, List.of(new MavenOutputProcessor()));
        final var output = String.join(
                "\n",
                "[INFO] Scanning for projects...",
                "[INFO] Building yca-core 1.0.0-SNAPSHOT",
                "[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0",
                "[ERROR] cannot find symbol",
                "[INFO] BUILD FAILURE");
        final var reports = new ArrayList<String>();
        final var result = service.execute(
                tool("bash", output), Map.of("command", "sudo timeout 120 mvn package"), context(reports));
        assertEquals(1, reports.size(), reports.toString());
        assertTrue(reports.get(0).contains("output optimized by MavenOutputProcessor"), reports.toString());
        // the maven processor collapsed the run: only the decisive lines survive, noise is gone
        assertTrue(result.output().contains("[INFO] BUILD FAILURE"));
        assertFalse(result.output().contains("Scanning for projects..."));
    }

    @Test
    void commandWrapperUnwrappingCanBeDisabled() {
        // without the wrapper stripping the head stays `sudo` and maven's supports() is false, so the
        // output is left unoptimized (no processable command matches, no reduction is reported)
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                Map.of("yca.storageHome", storageHome.toString(), "yca.optimize.skipCommandWrapper", "false"));
        final var service = serviceWith(configuration, List.of(new MavenOutputProcessor()));
        final var output = String.join(
                "\n",
                "[INFO] Scanning for projects...",
                "[INFO] Building yca-core 1.0.0-SNAPSHOT",
                "[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0",
                "[ERROR] cannot find symbol",
                "[INFO] BUILD FAILURE");
        final var reports = new ArrayList<String>();
        final var result = service.execute(
                tool("bash", output), Map.of("command", "sudo timeout 120 mvn package"), context(reports));
        // maven must not have been engaged (head stayed `sudo`), so nothing meaningful was optimized away
        assertTrue(reports.isEmpty(), reports.toString());
        assertTrue(result.output().contains("Scanning for projects..."));
    }

    private ToolExecutionService serviceWith(
            final CodingAgentConfiguration configuration,
            final List<io.yupiik.ia.agent.core.optimize.OutputProcessor> processors) {
        return new ToolExecutionService(
                configuration,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        // no-op
                    }
                },
                processors);
    }
}
