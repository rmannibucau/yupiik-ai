package io.yupiik.ia.agent.core.tools;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
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

    public ToolExecutionService(final CodingAgentConfiguration configuration, final Emitter emitter,
                                final List<OutputProcessor> processors, final StatusReporter statusReporter) {
        this.configuration = configuration;
        this.emitter = emitter;
        this.processors = processors;
        this.statusReporter = statusReporter;
    }

    protected ToolExecutionService() {
        this.configuration = null;
        this.emitter = null;
        this.processors = null;
        this.statusReporter = null;
    }

    public ToolResult execute(final EmbeddedTool tool, final Map<String, Object> args) {
        final var optimize = configuration.optimize();
        final var optimizeThis = optimize.enabled() && !optimize.isDisabled(tool.name());
        if (optimizeThis) {
            emitter.emit(new BeforeToolExecutionEvent(tool.name(), args));
        }
        ToolResult result;
        try {
            result = tool.execute(args);
        } catch (final RuntimeException | Error e) {
            result = ToolResult.error("Tool " + tool.name() + " failed: " + e);
        }
        var output = result.output();
        if (result.success() && optimizeThis) {
            output = optimizeOutput(tool, args, output);
        }
        if (optimizeThis) {
            emitter.emit(new AfterToolExecutionEvent(tool.name(), result.success(), output));
        }
        return new ToolResult(result.success(), output);
    }

    private String optimizeOutput(final EmbeddedTool tool, final Map<String, Object> args, final String output) {
        final var command = args.get("command") instanceof String s ? s : tool.name();
        final var sorted = processors.stream().sorted(Comparator.comparingInt(OutputProcessor::order)).toList();
        for (final var processor : sorted) {
            if (configuration.optimize().isDisabled(processor.getClass().getSimpleName())) {
                continue;
            }
            try {
                if (processor.supports(command)) {
                    final var processed = processor.process(command, output.lines())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    if (processed.length() < output.length()) {
                        statusReporter.report("output optimized by " + processor.getClass().getSimpleName()
                                + " (" + output.length() + " -> " + processed.length() + " chars)");
                        return processed;
                    }
                    return output;
                }
            } catch (final RuntimeException e) {
                return output;
            }
        }
        return output;
    }
}
