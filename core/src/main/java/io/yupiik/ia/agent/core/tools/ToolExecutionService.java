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

import static java.util.stream.Collectors.joining;

import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.BeforeToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.CommandOutputPostProcessor;
import io.yupiik.ia.agent.core.optimize.OutputProcessor;
import io.yupiik.ia.agent.core.security.ToolExecutionPrepare;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ToolExecutionService {
    private static final Logger LOGGER = Logger.getLogger(ToolExecutionService.class.getName());

    private final CodingAgentConfiguration configuration;
    private final Emitter emitter;
    private final List<OutputProcessor> processors;
    private final CommandOutputPostProcessor commandOutput;
    private final String subagentId;
    private final String subagentName;

    public ToolExecutionService(
            final CodingAgentConfiguration configuration,
            final Emitter emitter,
            final List<OutputProcessor> processors,
            final CommandOutputPostProcessor commandOutput) {
        this(configuration, emitter, processors, commandOutput, null, null);
    }

    public ToolExecutionService(
            final CodingAgentConfiguration configuration,
            final Emitter emitter,
            final List<OutputProcessor> processors) {
        this(configuration, emitter, processors, null, null, null);
    }

    protected ToolExecutionService(
            final CodingAgentConfiguration configuration,
            final Emitter emitter,
            final List<OutputProcessor> processors,
            final CommandOutputPostProcessor commandOutput,
            final String subagentId,
            final String subagentName) {
        this.configuration = configuration;
        this.emitter = emitter;
        this.processors = processors;
        // single defaulting point: an injected instance is used as-is, a hand-wired or
        // test construction without one falls back to a fresh stateless processor
        this.commandOutput = commandOutput == null ? new CommandOutputPostProcessor() : commandOutput;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
    }

    protected ToolExecutionService() {
        this(null, null, null, null, null, null);
    }

    public ToolResult execute(final EmbeddedTool tool, final Map<String, Object> args) {
        return execute(tool, args, ToolContext.empty());
    }

    /**
     * Executes a tool with the session context of the running turn: timing events are tagged with
     * that session (and subagent, when any) and the output-optimization notices are reported through
     * the context's own status reporter so they land in the tab that owns the turn.
     */
    public ToolResult execute(final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
        final var ctx = context == null ? ToolContext.empty() : context;
        final var optimize = configuration.optimize();
        final var optimizeThis = optimize.enabled() && !commandOutput.isDisabled(optimize, List.of(tool.name()));
        if (optimizeThis) {
            try {
                emitter.emit(new BeforeToolExecutionEvent(
                        tool.name(), args, ctx.sessionId(), ctx.subagentId(), ctx.subagentName()));
            } catch (final RuntimeException | Error e) {
                // an optimization/observability observer must not abort the tool or leave a running card open
                LOGGER.log(Level.WARNING, "before-tool execution event observer failed for '" + tool.name() + "'", e);
            }
        }
        final var startNanos = System.nanoTime();
        ToolResult result;
        try {
            // secret redaction: restore provenance fakes in the tool args back to the real values
            // right before execution so commands that need a credential still work (the provider
            // never saw the real value). Only registry-tracked fakes are restored.
            emitter.emit(new ToolExecutionPrepare(tool.name(), args, ctx.sessionId()));
            result = tool.execute(args, ctx);
        } catch (final RuntimeException | Error e) {
            result = ToolResult.error("Tool " + tool.name() + " failed: " + e);
        }
        final var durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        var output = result.output();
        if (result.success() && optimizeThis) {
            output = optimizeOutput(tool, args, output, ctx);
        }
        if (optimizeThis) {
            try {
                emitter.emit(new AfterToolExecutionEvent(
                        tool.name(), result.success(), output, ctx.sessionId(), ctx.subagentId(), ctx.subagentName()));
            } catch (final RuntimeException | Error e) {
                // a ToolUsePost hook (or any other after-tool observer) failing must never lose the tool result:
                // the outcome is still returned to the caller so the card can close normally.
                LOGGER.log(Level.WARNING, "after-tool execution event observer failed for '" + tool.name() + "'", e);
            }
        }
        try {
            emitter.emit(new AgentEvent.ToolTimingEvent(
                    tool.name(), durationMs, durationMs, false, ctx.sessionId(), ctx.subagentId(), ctx.subagentName()));
        } catch (final RuntimeException | Error e) {
            // a timing/statistics observer failing is non-fatal for the tool itself (it already ran to completion)
            LOGGER.log(Level.WARNING, "tool timing event observer failed for '" + tool.name() + "'", e);
        }
        // preserve the tri-state (OK/PENDING/ERROR) so a PENDING result is not collapsed to ERROR:
        // use the status-preserving copy rather than the legacy boolean constructor.
        return new ToolResult(result.status(), output);
    }

    private String optimizeOutput(
            final EmbeddedTool tool, final Map<String, Object> args, final String output, final ToolContext context) {
        final var reporter = context.statusReporter();
        final var command = args.get("command") instanceof String s ? s : tool.name();
        final var optimize = configuration.optimize();
        // Peek through a leading wrapper (sudo, timeout 120, env VAR=.., command, sh -c ...) so the
        // command head matchers see the inner command: `timeout 120 mvn ...` is optimized as `mvn ...`.
        final var effective = optimize.skipCommandWrapper()
                ? commandOutput.unwrapCommandWrapper(command).orElse(command)
                : command;
        final var minReduction = Math.max(0, Math.min(100, optimize.minReductionPercent()));
        final var sorted = processors.stream()
                .filter(p -> p.appliesToTool(tool.name()))
                .sorted(Comparator.comparingInt(OutputProcessor::order))
                .toList();
        for (final var processor : sorted) {
            if (commandOutput.isDisabled(optimize, processor.names())) {
                continue;
            }
            try {
                if (processor.supports(effective)) {
                    final var processed =
                            processor.process(effective, output.lines()).collect(joining("\n"));
                    // only accept a meaningful reduction: tiny wins are noise, not optimization
                    if (processed.length() * 100L <= output.length() * (100L - minReduction)) {
                        if (reporter != null) {
                            reporter.report("output optimized by " + processor.displayName() + " (" + output.length()
                                    + " -> " + processed.length() + " chars)");
                        }
                        if (context.usage() != null) {
                            context.usage().recordOptimization(
                                    output.length(),
                                    processed.length(),
                                    TokenEstimator.estimate(output) - TokenEstimator.estimate(processed));
                        }
                        return processed;
                    }
                    // Try the next processor when this reduction is below the threshold.
                }
            } catch (final RuntimeException e) {
                // this processor failed: fall through to the next candidate
            }
        }
        return output;
    }
}
