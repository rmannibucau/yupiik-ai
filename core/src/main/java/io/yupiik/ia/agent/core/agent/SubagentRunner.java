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
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.SubagentUsageTracker;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
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
    protected final ContextWindowCache contextWindowCache;
    protected final MemoryStore memoryStore;
    protected final Emitter emitter;
    protected final JsonMapper mapper;
    protected final StructuralCodeGraph structuralCodeGraph;
    protected final ProviderManager providerManager;
    protected final AgentProvider agentProvider;
    protected final TodoPromptResolver todoPromptResolver;
    protected final MavenRepositoryKnowledgeService mavenRepositoryKnowledge;
    protected final Clock clock;
    protected final VirtualThreadExecutor executor;
    protected final SubagentRegistry subagentRegistry;

    public SubagentRunner(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final ContextWindowCache contextWindowCache,
            final MemoryStore memoryStore,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final TodoPromptResolver todoPromptResolver,
            final MavenRepositoryKnowledgeService mavenRepositoryKnowledge,
            final Clock clock,
            final VirtualThreadExecutor executor,
            final SubagentRegistry subagentRegistry) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.contextWindowCache = contextWindowCache;
        this.memoryStore = memoryStore;
        this.emitter = emitter;
        this.mapper = mapper;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.todoPromptResolver = todoPromptResolver;
        this.mavenRepositoryKnowledge = mavenRepositoryKnowledge;
        this.clock = clock;
        this.executor = executor;
        this.subagentRegistry = subagentRegistry;
    }

    protected SubagentRunner() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.contextWindowCache = null;
        this.memoryStore = null;
        this.emitter = null;
        this.mapper = null;
        this.structuralCodeGraph = null;
        this.providerManager = null;
        this.agentProvider = null;
        this.todoPromptResolver = null;
        this.mavenRepositoryKnowledge = null;
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
        runAsync(state, parentSessionStore, parentSnapshotStore, parentTodoStore, null, cancelled);
    }

    /**
     * @param parentContext the spawning session's tool context: the sub-agent
     *                      inherits the parent's permission state and feedback gate
     *                      (so a question raised by a sub-agent answers in the
     *                      spawning tab) and its events carry the parent session
     *                      id so they route back to that tab.
     */
    public void runAsync(
            final SubagentRegistry.SubagentState state,
            final SessionStore parentSessionStore,
            final SnapshotStore parentSnapshotStore,
            final TodoStore parentTodoStore,
            final ToolContext parentContext,
            final BooleanSupplier cancelled) {
        executor.execute(() -> {
            try {
                final var result = runSubagent(state, parentSnapshotStore, parentContext, cancelled);
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
            final ToolContext parentContext,
            final BooleanSupplier cancelled) {
        final var subagentSessionId = state.parentSessionId() + "-subagent-" + state.id();
        final var subagentName = state.name() != null ? state.name() : state.id();

        // Create isolated services for this subagent: explicit instances built from the shared beans and the
        // spawning session's context (the parent permission state, feedback gate and usage are inherited).
        final var subagentSessionStore = new InMemorySessionStore(mapper, clock);
        final var subagentTodoStore = new InMemoryTodoStore();
        final var parentUsage = parentContext != null && parentContext.usage() != null
                ? parentContext.usage()
                : new UsageTracker(providerManager, chatClient, contextWindowCache, clock);
        final var subagentUsageTracker = new SubagentUsageTracker(parentUsage, state.id());
        final var subagentStatusReporter = new StatusReporter();
        final var subagentPermissions = parentContext != null && parentContext.permissions() != null
                ? parentContext.permissions()
                : new PermissionService(configuration);
        final var subagentSystemPromptBuilder = new SystemPromptBuilder(
                configuration,
                memoryStore,
                subagentTodoStore,
                subagentPermissions,
                agentProvider,
                providerManager,
                todoPromptResolver,
                mavenRepositoryKnowledge);
        final var subagentMemoryExtractor = new MemoryExtractor(
                chatClient,
                configuration,
                memoryStore,
                mapper,
                executor,
                emitter,
                clock,
                subagentUsageTracker,
                state.parentSessionId());
        final var subagentContextView = new ContextView();
        final var subagentCompactionCoordinator = new CompactionCoordinator(
                configuration, chatClient, subagentUsageTracker, providerManager, emitter, state.parentSessionId());
        final var subagentContextManager =
                new ContextManager(configuration, subagentUsageTracker, subagentCompactionCoordinator);

        final var agentLoop = new AgentLoop(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                subagentPermissions,
                subagentUsageTracker,
                subagentContextManager,
                subagentContextView,
                subagentSessionStore,
                subagentSystemPromptBuilder,
                subagentMemoryExtractor,
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
                state.parentSessionId(),
                state.id(),
                subagentName,
                parentContext == null ? null : parentContext.feedback());

        final var fullConversation = new StringBuilder();
        fullConversation
                .append("System: ")
                .append(subagentSystemPromptBuilder.build(state.task(), null))
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
