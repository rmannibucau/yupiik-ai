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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.BeforeToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.OutputProcessor;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ToolExecutionService {
    private final CodingAgentConfiguration configuration;
    private final Emitter emitter;
    private final List<OutputProcessor> processors;
    private final StatusReporter statusReporter;
    private final String subagentId;
    private final String subagentName;

    public ToolExecutionService(
            final CodingAgentConfiguration configuration,
            final Emitter emitter,
            final List<OutputProcessor> processors,
            final StatusReporter statusReporter) {
        this(configuration, emitter, processors, statusReporter, null, null);
    }

    protected ToolExecutionService(
            final CodingAgentConfiguration configuration,
            final Emitter emitter,
            final List<OutputProcessor> processors,
            final StatusReporter statusReporter,
            final String subagentId,
            final String subagentName) {
        this.configuration = configuration;
        this.emitter = emitter;
        this.processors = processors;
        this.statusReporter = statusReporter;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
    }

    protected ToolExecutionService() {
        this.configuration = null;
        this.emitter = null;
        this.processors = null;
        this.statusReporter = null;
        this.subagentId = null;
        this.subagentName = null;
    }

    public ToolResult execute(final EmbeddedTool tool, final Map<String, Object> args) {
        final var optimize = configuration.optimize();
        final var optimizeThis = optimize.enabled() && !optimize.isDisabled(List.of(tool.name()));
        if (optimizeThis) {
            emitter.emit(new BeforeToolExecutionEvent(tool.name(), args));
        }
        final var startNanos = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(args);
        } catch (final RuntimeException | Error e) {
            result = ToolResult.error("Tool " + tool.name() + " failed: " + e);
        }
        final var durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        var output = result.output();
        if (result.success() && optimizeThis) {
            output = optimizeOutput(tool, args, output);
        }
        if (optimizeThis) {
            emitter.emit(new AfterToolExecutionEvent(tool.name(), result.success(), output));
        }
        emitter.emit(
                new AgentEvent.ToolTimingEvent(tool.name(), durationMs, durationMs, false, subagentId, subagentName));
        return new ToolResult(result.success(), output);
    }

    private String optimizeOutput(final EmbeddedTool tool, final Map<String, Object> args, final String output) {
        final var command = args.get("command") instanceof String s ? s : tool.name();
        final var optimize = configuration.optimize();
        final var minReduction = Math.max(0, Math.min(100, optimize.minReductionPercent()));
        final var sorted = processors.stream()
                .filter(p -> p.appliesToTool(tool.name()))
                .sorted(Comparator.comparingInt(OutputProcessor::order))
                .toList();
        for (final var processor : sorted) {
            if (optimize.isDisabled(processor.names())) {
                continue;
            }
            try {
                if (processor.supports(command)) {
                    final var processed = processor
                            .process(command, output.lines())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    // only accept a meaningful reduction: tiny wins are noise, not optimization
                    if (processed.length() * 100L <= output.length() * (100L - minReduction)) {
                        statusReporter.report("output optimized by " + processor.displayName() + " (" + output.length()
                                + " -> " + processed.length() + " chars)");
                        return processed;
                    }
                    return output;
                }
            } catch (final RuntimeException e) {
                // this processor failed: fall through to the next candidate
            }
        }
        return output;
    }
}
