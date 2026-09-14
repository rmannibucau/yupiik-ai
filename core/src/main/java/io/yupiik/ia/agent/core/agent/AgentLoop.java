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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.Usage;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

@ApplicationScoped
public class AgentLoop {
    private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());

    /** Anti-thrash: stop auto-compacting when the context refills right after compaction this many turns in a row. */
    private static final int MAX_CONSECUTIVE_COMPACTIONS = 5;

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
    private final Clock clock;
    private final SnapshotStore snapshotStore;

    public AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore) {
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
        this.clock = clock;
        this.snapshotStore = snapshotStore;
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
        this.clock = null;
        this.snapshotStore = null;
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        final var agent = (agentName == null || agentName.isBlank() || agentProvider == null)
                ? null
                : agentProvider.byName(agentName);
        structuralCodeGraph.ensureReady();
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(prompt, agent) + snapshotBlock(sessionId)));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Message.user(prompt));
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "user", prompt, null, null));
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

    private String loop(final String sessionId, final List<Message> messages, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var retryOnOverflow = configuration.llm().retryOnContextOverflow();
        final var maxIterations = configuration.llm().maxIterations();
        final var maxConsecutiveCompactions = MAX_CONSECUTIVE_COMPACTIONS;
        final var model = providerManager.resolveDefault().model();
        final var tools = toolRegistry.toWireTools();
        final var toolsTokens = toolTokens(tools);
        var overflowRetried = false;
        var consecutiveCompactions = 0;
        var autoNoticeEmitted = false;
        for (var iteration = 0; iteration < maxIterations && !cancelled.getAsBoolean(); iteration++) {
            var compactedThisTurn = false;
            if (contextManager.pruneNeeded(messages, toolsTokens)) {
                final var pruned = contextManager.prune(messages);
                if (pruned.changed()) {
                    messages.clear();
                    messages.addAll(pruned.messages());
                    emit(new AgentEvent.Compacted("prune", pruned.mode().name()));
                    compactedThisTurn = true;
                }
            }
            if (contextManager.compactNeeded(messages, toolsTokens)) {
                final var compacted = contextManager.compact(messages);
                if (compacted.changed()) {
                    messages.clear();
                    messages.addAll(compacted.messages());
                    emit(new AgentEvent.Compacted("compact", compacted.mode().name()));
                    compactedThisTurn = true;
                }
            }
            if (compactedThisTurn) {
                consecutiveCompactions++;
                if (consecutiveCompactions >= maxConsecutiveCompactions
                        && usageTracker.contextPercent(usageTracker.estimateContext(messages, toolsTokens))
                                >= configuration.compaction().thresholdPercent()) {
                    emit(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.ERROR,
                            "Context keeps filling up right after compaction after " + consecutiveCompactions
                                    + " attempts. Consider /compact manually, a larger model, or splitting the task."));
                    break;
                }
            } else {
                consecutiveCompactions = 0;
            }

            final var request = new ChatCompletionRequest(model, messages, null, null, tools, "auto", null, null, null);
            final var usage = new io.yupiik.ia.agent.core.llm.Usage[] {io.yupiik.ia.agent.core.llm.Usage.EMPTY};
            final Message assistant;
            try {
                assistant = chatClient.stream(request, delta -> dispatch(delta, usage), cancelled);
            } catch (final ContextWindowExceededException e) {
                if (!retryOnOverflow || overflowRetried) {
                    throw e;
                }
                if (usageTracker.lastResolutionWasAuto() && !autoNoticeEmitted) {
                    emit(
                            new AgentEvent.Notice(
                                    AgentEvent.Notice.Level.INFO,
                                    "Context window auto-detected: no exact figure was available, using a conservative default and learning on overflow."));
                    autoNoticeEmitted = true;
                }
                final var shrunken = usageTracker.rememberShrinkOnOverflow();
                emit(new AgentEvent.Notice(
                        AgentEvent.Notice.Level.INFO,
                        "Context window exceeded; compacting and retrying (assumed window reduced to " + shrunken
                                + " tokens)."));
                final var compacted = contextManager.compactForOverflow(messages);
                if (compacted.changed() && !compacted.messages().isEmpty()) {
                    messages.clear();
                    messages.addAll(compacted.messages());
                    emit(new AgentEvent.Compacted(
                            "compact-retry", compacted.mode().name()));
                    overflowRetried = true;
                    continue;
                }
                emit(new AgentEvent.Notice(
                        AgentEvent.Notice.Level.ERROR,
                        "Context window exceeded and compacting did not free enough context: " + e.getMessage()));
                throw e;
            }
            usageTracker.record(usage[0]);
            messages.add(assistant);
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(
                            clock.millis(), "assistant", str(assistant.content()), assistant.toolCalls(), null));
            emitUsage(messages, toolsTokens);

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
        final var name = call.function() == null || call.function().name() == null
                ? ""
                : call.function().name();
        final var argsJson = call.function() == null || call.function().arguments() == null
                ? "{}"
                : call.function().arguments();
        final EmbeddedTool tool = toolRegistry.tool(name);
        if (tool == null) {
            messages.add(Message.tool(call.id(), "Unknown tool: " + name));
            emit(new AgentEvent.ToolFinished(name, unknownToolDiagnostic(name), false));
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
        final PermissionService.Decision decision =
                permissionService.check(name, tool.category(), args, tool.readonly());
        if (!decision.allowed()) {
            final var denial =
                    "Permission denied by user" + (decision.reason() == null ? "" : ": " + decision.reason());
            messages.add(Message.tool(call.id(), denial));
            emit(new AgentEvent.ToolFinished(name, denial, false));
            return;
        }
        final ToolResult result = toolExecutionService.execute(tool, args);
        messages.add(Message.tool(
                call.id(), result.output() == null || result.output().isBlank() ? "(no output)" : result.output()));
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "tool", result.output(), null, name));
        emit(new AgentEvent.ToolFinished(name, result.output(), result.success()));
    }

    private static String unknownToolDiagnostic(final String name) {
        return "Unknown tool \"" + name + "\": not in this session's toolset (built-in or enabled MCP)."
                + " Run /tools to list the valid tools; only exact names from the request's tools field are accepted.";
    }

    private void dispatch(final LlmStreamEvent delta, final io.yupiik.ia.agent.core.llm.Usage[] usage) {
        if (delta instanceof TextDelta(final String text)) {
            emit(new AgentEvent.TextDelta(text));
        } else if (delta instanceof ReasoningDelta(final String text)) {
            emit(new AgentEvent.ThinkingDelta(text));
        } else if (delta instanceof End(final String __, final Usage upper)) {
            final var converted = upper == null
                    ? io.yupiik.ia.agent.core.llm.Usage.EMPTY
                    : new io.yupiik.ia.agent.core.llm.Usage(
                            Math.toIntExact(upper.promptTokens()),
                            Math.toIntExact(upper.completionTokens()),
                            Math.toIntExact(upper.totalTokens()));
            usage[0] = converted;
            usageTracker.record(converted);
        }
    }

    private void emitUsage(final List<Message> messages, final int toolsTokens) {
        final var estimated = usageTracker.estimateContext(messages, toolsTokens);
        emit(new AgentEvent.UsageUpdated(
                usageTracker.promptTokens() > 0
                        ? new io.yupiik.ia.agent.core.llm.Usage(
                                (int) usageTracker.promptTokens(), (int) usageTracker.completionTokens(), (int)
                                        (usageTracker.promptTokens() + usageTracker.completionTokens()))
                        : io.yupiik.ia.agent.core.llm.Usage.EMPTY,
                estimated,
                usageTracker.contextPercent(estimated)));
    }

    /**
     * Estimates the token contribution of the wire tool schemas injected into every request,
     * which are never part of the message list but consume context window. Falls back to a rough
     * per-tool cost when the JSON mapper is unavailable.
     */
    private int toolTokens(final List<?> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        if (mapper != null) {
            try {
                return TokenEstimator.estimate(mapper.toString(tools));
            } catch (final RuntimeException ignored) {
                // fall through to the per-tool approximation
            }
        }
        int total = 0;
        for (final var ignored : tools) {
            total += 300;
        }
        return total;
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }

    /**
     * Appends the per-session snapshot block (active checkpoint label plus retained summaries that
     * must survive compaction) to the system prompt, when any exist.
     */
    private String snapshotBlock(final String sessionId) {
        if (snapshotStore == null || sessionId == null) {
            return "";
        }
        final var sb = new StringBuilder();
        final var label = snapshotStore.checkpointLabel(sessionId);
        if (label != null && !label.isBlank()) {
            sb.append("\n<snapshot checkpoint=\"").append(label).append("\">\n");
        } else {
            sb.append("\n<snapshot>\n");
        }
        final var retained = snapshotStore.retained(sessionId);
        if (retained == null || retained.isEmpty()) {
            sb.append("checkpoint: ")
                    .append(label == null || label.isEmpty() ? "none" : label)
                    .append('\n');
        } else {
            for (final var r : retained) {
                final var summary = r.summary();
                sb.append("- ")
                        .append(summary == null ? "" : summary.replace("\n", " "))
                        .append('\n');
            }
        }
        sb.append("</snapshot>\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
