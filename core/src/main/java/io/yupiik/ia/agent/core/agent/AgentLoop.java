package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ContextWindowExceededException;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.StreamDelta;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

@ApplicationScoped
public class AgentLoop {
    private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final CodingAgentConfiguration configuration;
    private final PermissionService permissionService;
    private final UsageTracker usageTracker;
    private final ContextManager contextManager;
    private final SessionStore sessionStore;
    private final SystemPromptBuilder systemPromptBuilder;
    private final MemoryExtractor memoryExtractor;
    private final StatusReporter statusReporter;
    private final Emitter emitter;
    private final JsonMapper mapper;
    private final StructuralCodeGraph structuralCodeGraph;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;

    public AgentLoop(final ChatClient chatClient, final ToolRegistry toolRegistry,
                     final ToolExecutionService toolExecutionService, final CodingAgentConfiguration configuration,
                     final PermissionService permissionService, final UsageTracker usageTracker,
                     final ContextManager contextManager, final SessionStore sessionStore,
                     final SystemPromptBuilder systemPromptBuilder, final MemoryExtractor memoryExtractor,
                     final StatusReporter statusReporter, final Emitter emitter, final JsonMapper mapper,
                     final StructuralCodeGraph structuralCodeGraph, final ProviderManager providerManager,
                     final AgentProvider agentProvider) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.permissionService = permissionService;
        this.usageTracker = usageTracker;
        this.contextManager = contextManager;
        this.sessionStore = sessionStore;
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryExtractor = memoryExtractor;
        this.statusReporter = statusReporter;
        this.emitter = emitter;
        this.mapper = mapper;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
    }

    protected AgentLoop() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.permissionService = null;
        this.usageTracker = null;
        this.contextManager = null;
        this.sessionStore = null;
        this.systemPromptBuilder = null;
        this.memoryExtractor = null;
        this.statusReporter = null;
        this.emitter = null;
        this.mapper = null;
        this.structuralCodeGraph = null;
        this.providerManager = null;
        this.agentProvider = null;
    }

    public String run(final String prompt, final String sessionId, final List<Message> history,
                      final BooleanSupplier cancelled) {
        return run(prompt, null, sessionId, history, cancelled);
    }

    public String run(final String prompt, final String agentName, final String sessionId, final List<Message> history,
                      final BooleanSupplier cancelled) {
        final var agent = (agentName == null || agentName.isBlank() || agentProvider == null)
                ? null : agentProvider.byName(agentName);
        structuralCodeGraph.ensureReady();
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(prompt, agent)));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Message.user(prompt));
        sessionStore.append(sessionId, SessionEntry.of(System.currentTimeMillis(), "user", prompt, null, null));
        statusReporter.setSink(message -> emitter.emit(new AgentEvent.Status(message)));

        try {
            return loop(sessionId, messages, cancelled);
        } catch (final IOException e) {
            emit(new AgentEvent.Error(e.getMessage()));
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            emit(new AgentEvent.Error("Interrupted"));
            return null;
        } finally {
            statusReporter.setSink(null);
            memoryExtractor.maybeExtract(messages);
        }
    }

    private void emit(final AgentEvent event) {
        emitter.emit(event);
    }

    private String loop(final String sessionId, final List<Message> messages,
                        final BooleanSupplier cancelled) throws IOException, InterruptedException {
        final var retryOnOverflow = configuration.llm().retryOnContextOverflow();
        final var maxIterations = configuration.llm().maxIterations();
        var overflowRetried = false;
        for (var iteration = 0; iteration < maxIterations && !cancelled.getAsBoolean(); iteration++) {
            if (contextManager.pruneNeeded(messages)) {
                final var pruned = contextManager.prune(messages);
                if (pruned != messages) {
                    messages.clear();
                    messages.addAll(pruned);
                    emit(new AgentEvent.Compacted("prune"));
                }
            }
            if (contextManager.compactNeeded(messages)) {
                final var compacted = contextManager.compact(messages);
                if (compacted != messages) {
                    messages.clear();
                    messages.addAll(compacted);
                    emit(new AgentEvent.Compacted("compact"));
                }
            }

            final var request = new ChatCompletionRequest(providerManager.resolveDefault().model(), messages,
                    null, null, toolRegistry.toWireTools(), "auto", null, null, null);
            final var usage = new io.yupiik.ia.agent.core.llm.Usage[]{io.yupiik.ia.agent.core.llm.Usage.EMPTY};
            final Message assistant;
            try {
                assistant = chatClient.stream(request, delta -> dispatch(delta, usage), cancelled);
            } catch (final ContextWindowExceededException e) {
                if (!retryOnOverflow || overflowRetried) {
                    throw e;
                }
                emit(new AgentEvent.Status("Context window exceeded; compacting and retrying."));
                final var compacted = contextManager.compactForOverflow(messages);
                if (compacted != messages && !compacted.isEmpty()) {
                    messages.clear();
                    messages.addAll(compacted);
                    emit(new AgentEvent.Compacted("compact-retry"));
                    overflowRetried = true;
                    continue;
                }
                throw e;
            }
            usageTracker.record(usage[0]);
            messages.add(assistant);
            sessionStore.append(sessionId, SessionEntry.of(System.currentTimeMillis(),
                    "assistant", str(assistant.content()), assistant.toolCalls(), null));
            emitUsage(messages);

            if (assistant.toolCalls() == null || assistant.toolCalls().isEmpty()) {
                final var content = str(assistant.content());
                emit(new AgentEvent.TurnDone(content));
                return content;
            }
            for (final var call : assistant.toolCalls()) {
                executeTool(sessionId, messages, call);
            }
        }
        if (cancelled.getAsBoolean()) {
            emit(new AgentEvent.Error("Cancelled"));
        } else {
            emit(new AgentEvent.Error("Maximum iterations reached (" + maxIterations + ")"));
        }
        return null;
    }

    private void executeTool(final String sessionId, final List<Message> messages, final ToolCall call) {
        final var name = call.function() == null ? "" : call.function().name();
        final var argsJson = call.function() == null || call.function().arguments() == null
                ? "{}" : call.function().arguments();
        final EmbeddedTool tool = toolRegistry.tool(name);
        if (tool == null) {
            messages.add(Message.tool(call.id(), "Unknown tool: " + name));
            emit(new AgentEvent.ToolFinished(name, "unknown tool", false));
            return;
        }
        final Map<String, Object> args;
        try {
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
        } catch (final RuntimeException e) {
            messages.add(Message.tool(call.id(), "Invalid tool arguments JSON: " + argsJson));
            emit(new AgentEvent.ToolFinished(name, "invalid arguments", false));
            return;
        }
        emit(new AgentEvent.ToolStarted(name, argsJson));
        final PermissionService.Decision decision = permissionService.check(name, tool.category(), args, tool.readonly());
        if (!decision.allowed()) {
            final var denial = "Permission denied by user" + (decision.reason() == null ? "" : ": " + decision.reason());
            messages.add(Message.tool(call.id(), denial));
            emit(new AgentEvent.ToolFinished(name, denial, false));
            return;
        }
        final ToolResult result = toolExecutionService.execute(tool, args);
        messages.add(Message.tool(call.id(), result.output() == null || result.output().isBlank() ? "(no output)" : result.output()));
        sessionStore.append(sessionId, SessionEntry.of(System.currentTimeMillis(), "tool",
                result.output(), null, name));
        emit(new AgentEvent.ToolFinished(name, result.output(), result.success()));
    }

    private void dispatch(final StreamDelta delta, final io.yupiik.ia.agent.core.llm.Usage[] usage) {
        switch (delta.type()) {
            case TEXT -> emit(new AgentEvent.TextDelta(delta.text()));
            case REASONING -> emit(new AgentEvent.ThinkingDelta(delta.text()));
            case USAGE -> {
                usage[0] = delta.usage();
                usageTracker.record(delta.usage());
            }
            default -> {
            }
        }
    }

    private void emitUsage(final List<Message> messages) {
        final var estimated = usageTracker.estimateContext(messages);
        emit(new AgentEvent.UsageUpdated(usageTracker.promptTokens() > 0
                ? new io.yupiik.ia.agent.core.llm.Usage((int) usageTracker.promptTokens(), (int) usageTracker.completionTokens(),
                (int) (usageTracker.promptTokens() + usageTracker.completionTokens()))
                : io.yupiik.ia.agent.core.llm.Usage.EMPTY, estimated, usageTracker.contextPercent(estimated)));
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
