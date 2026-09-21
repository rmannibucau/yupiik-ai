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
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.SubagentUsageTracker;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

@ApplicationScoped
public class SubagentRunner {
    protected final ChatClient chatClient;
    protected final ToolRegistry toolRegistry;
    protected final ToolExecutionService toolExecutionService;
    protected final CodingAgentConfiguration configuration;
    protected final PermissionService permissionService;
    protected final UsageTracker usageTracker;
    protected final ContextManager contextManager;
    protected final ContextView contextView;
    protected final SystemPromptBuilder systemPromptBuilder;
    protected final MemoryExtractor memoryExtractor;
    protected final StatusReporter statusReporter;
    protected final Emitter emitter;
    protected final JsonMapper mapper;
    protected final StructuralCodeGraph structuralCodeGraph;
    protected final ProviderManager providerManager;
    protected final AgentProvider agentProvider;
    protected final Clock clock;
    protected final VirtualThreadExecutor executor;
    protected final SubagentRegistry subagentRegistry;

    public SubagentRunner(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final ContextManager contextManager,
            final ContextView contextView,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final VirtualThreadExecutor executor,
            final SubagentRegistry subagentRegistry) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.permissionService = permissionService;
        this.usageTracker = usageTracker;
        this.contextManager = contextManager;
        this.contextView = contextView;
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryExtractor = memoryExtractor;
        this.statusReporter = statusReporter;
        this.emitter = emitter;
        this.mapper = mapper;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.clock = clock;
        this.executor = executor;
        this.subagentRegistry = subagentRegistry;
    }

    protected SubagentRunner() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.permissionService = null;
        this.usageTracker = null;
        this.contextManager = null;
        this.contextView = null;
        this.systemPromptBuilder = null;
        this.memoryExtractor = null;
        this.statusReporter = null;
        this.emitter = null;
        this.mapper = null;
        this.structuralCodeGraph = null;
        this.providerManager = null;
        this.agentProvider = null;
        this.clock = null;
        this.executor = null;
        this.subagentRegistry = null;
    }

    public void runAsync(
            final SubagentRegistry.SubagentState state,
            final SessionStore parentSessionStore,
            final SnapshotStore parentSnapshotStore,
            final TodoStore parentTodoStore,
            final BooleanSupplier cancelled) {
        executor.execute(() -> {
            try {
                final var result = runSubagent(state, parentSnapshotStore, cancelled);
                subagentRegistry.complete(state.id(), result);
            } catch (final Exception e) {
                subagentRegistry.complete(
                        state.id(), SubagentRegistry.SubagentResult.error("Subagent failed: " + e.getMessage(), ""));
            }
        });
    }

    private SubagentRegistry.SubagentResult runSubagent(
            final SubagentRegistry.SubagentState state,
            final SnapshotStore parentSnapshotStore,
            final BooleanSupplier cancelled) {
        final var subagentSessionId = state.parentSessionId() + "-subagent-" + state.id();
        final var subagentName = state.name() != null ? state.name() : state.id();

        // Create isolated services for this subagent
        final var subagentSessionStore = new InMemorySessionStore(mapper, clock);
        final var subagentTodoStore = new InMemoryTodoStore();
        final var subagentUsageTracker = new SubagentUsageTracker(usageTracker, state.id());
        final var subagentStatusReporter = new StatusReporter();

        final var agentLoop = new AgentLoop(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                subagentUsageTracker,
                contextManager,
                contextView,
                subagentSessionStore,
                systemPromptBuilder,
                memoryExtractor,
                subagentStatusReporter,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                parentSnapshotStore, // snapshot store can be shared (per-session isolation)
                subagentTodoStore,
                executor,
                state.id(),
                subagentName);

        final var fullConversation = new StringBuilder();
        fullConversation
                .append("System: ")
                .append(systemPromptBuilder.build(state.task(), null))
                .append("\n\n");
        fullConversation.append("User: ").append(state.task()).append("\n\n");

        final BooleanSupplier subagentCancelled = () -> cancelled.getAsBoolean() || state.isCancelled();
        final var result = agentLoop.run(state.task(), null, subagentSessionId, List.of(), subagentCancelled);

        fullConversation
                .append("Assistant: ")
                .append(result != null ? result : "[no result]")
                .append("\n");

        // Extract artifacts from subagent's session (file paths from write/edit/apply_patch tools)
        final var artifacts = extractArtifacts(subagentSessionStore, subagentSessionId);

        // Merge subagent usage into parent's auxiliary counters
        subagentUsageTracker.mergeToParent();

        return result != null
                ? SubagentRegistry.SubagentResult.success(result, fullConversation.toString(), artifacts)
                : SubagentRegistry.SubagentResult.error(
                        "Subagent completed without result", fullConversation.toString());
    }

    private List<String> extractArtifacts(final SessionStore sessionStore, final String sessionId) {
        final var messages = sessionStore.load(sessionId);
        return messages.stream()
                .filter(m -> "tool".equals(m.role()))
                .filter(m -> m.toolCalls() != null)
                .flatMap(m -> m.toolCalls().stream())
                .filter(tc -> tc.function() != null)
                .filter(tc -> Set.of("write_file", "edit_file", "apply_patch")
                        .contains(tc.function().name()))
                .map(tc -> {
                    try {
                        final var args =
                                mapper.fromString(Map.class, tc.function().arguments());
                        return args.get("path") instanceof String p ? p : null;
                    } catch (final Exception e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }
}
