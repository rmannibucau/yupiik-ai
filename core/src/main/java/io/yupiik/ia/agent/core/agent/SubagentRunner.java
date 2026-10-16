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
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
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
    protected final PrecisionDurationMarkerProvider precisionDurationMarker;
    protected final ExecutorService executor;
    protected final SubagentRegistry subagentRegistry;
    protected final io.yupiik.ia.agent.core.security.SecretRedactor secretRedactor;

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
            final ExecutorService executor,
            final SubagentRegistry subagentRegistry,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final io.yupiik.ia.agent.core.security.SecretRedactor secretRedactor) {
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
        this.precisionDurationMarker = precisionDurationMarker;
        this.secretRedactor = secretRedactor;
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
        this.precisionDurationMarker = null;
        this.secretRedactor = null;
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
        final var name = state.name() != null ? state.name() : state.id();
        emitter.emit(new AgentEvent.SubagentStarted(state.task(), state.parentSessionId(), state.id(), name));
        executor.execute(() -> {
            try {
                final var result = runSubagent(state, parentSnapshotStore, parentContext, cancelled);
                subagentRegistry.complete(state.id(), result);
                emitter.emit(new AgentEvent.SubagentCompleted(
                        result.summary() != null ? result.summary() : result.error(),
                        result.success(),
                        result.cancelled(),
                        state.parentSessionId(),
                        state.id(),
                        name));
            } catch (final Exception e) {
                final var message = "Subagent failed: " + e.getMessage();
                final var wasCancelled = state.isCancelled() || cancelled.getAsBoolean();
                subagentRegistry.complete(
                        state.id(),
                        wasCancelled
                                ? SubagentRegistry.SubagentResult.cancelled("")
                                : SubagentRegistry.SubagentResult.error(message, ""));
                emitter.emit(new AgentEvent.SubagentCompleted(
                        message, false, wasCancelled, state.parentSessionId(), state.id(), name));
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
                ? parentContext.permissions().forkForChild()
                : new PermissionService(configuration, emitter);
        final var subagentSystemPromptBuilder = new SystemPromptBuilder(
                configuration,
                memoryStore,
                subagentTodoStore,
                subagentPermissions,
                agentProvider,
                providerManager,
                todoPromptResolver,
                mavenRepositoryKnowledge,
                secretRedactor);
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
                null, // sub-agents never run memory extraction: no user instructions there, and the parent
                // session's extractor (same shared MemoryStore) already captures durable facts surfaced
                // through the sub-agent result; a per-subagent extractor would only add duplicate calls
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
                parentContext == null ? null : parentContext.feedback(),
                precisionDurationMarker);
        // the sub-agent inherits its parent tab's provider/model/variant/sampling selection so a
        // child spawned from tab B runs on B's provider, not the process default. An explicit
        // model (spawn_subagent model=/defaultSubagentModel) overrides the selection: resolve the
        // reference to a concrete (provider, model) pair via the provider manager machinery.
        final var baseSettings =
                agentLoop.chatSettings().inheritFrom(parentContext == null ? null : parentContext.settings());
        ChatSettings subagentSettings = baseSettings;
        final var requested = state.model();
        if (requested != null && !requested.isBlank()) {
            subagentSettings = routeSubagentModel(providerManager, requested, baseSettings);
        } else {
            final var provider = parentContext == null || parentContext.settings() == null
                    ? null
                    : providerManager.provider(parentContext.settings().providerId());
            final var defaultModel = provider == null ? null : provider.defaultSubagentModel();
            if (defaultModel != null && !defaultModel.isBlank()) {
                subagentSettings = routeSubagentModel(providerManager, defaultModel, baseSettings);
            }
        }
        agentLoop.updateChatSettings(subagentSettings);

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

        // a null result on a cancelled turn is a cancellation, not a failure: the loop returns null
        // when the interrupt unwound it, and the parent transcript must render it as such
        if (result == null && (state.isCancelled() || cancelled.getAsBoolean())) {
            return SubagentRegistry.SubagentResult.cancelled(fullConversation.toString());
        }
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
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Routes a model reference to sub-agent chat settings: {@code $provider/model} selects that
     * provider explicitly; a bare name is pinned (via {@link ProviderManager#toRoutedReference})
     * to whichever configured provider declares it, else resolved against the parent's provider.
     * The parent's variant/sampling are preserved unless the routed model overrides them; passing
     * {@code ChatSettings.EMPTY} as base keeps the process defaults for the routed provider.
     */
    private ChatSettings routeSubagentModel(final ProviderManager pm, final String reference, final ChatSettings base) {
        final var ref = reference.strip();
        if (ref.startsWith("$")) {
            final var resolved = pm.resolve(ref);
            final var provider = resolved.provider();
            final var model = resolved.model() == null || resolved.model().isBlank()
                    ? (provider == null || provider.model() == null ? "" : provider.model())
                    : resolved.model();
            final var providerId =
                    provider == null || provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
            return ChatSettings.EMPTY
                    .withProviderId(providerId)
                    .withModel(model)
                    .inheritFrom(base);
        }
        // bare name: pin to the declaring provider when identifiable, else resolve against base
        final var routable = ref.startsWith("$") ? ref : pm.toRoutedReference(ref);
        if (routable.startsWith("$")) {
            final var resolved = pm.resolve(routable);
            final var provider = resolved.provider();
            final var providerId =
                    provider == null || provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
            final var model = resolved.model() == null || resolved.model().isBlank() ? ref : resolved.model();
            return ChatSettings.EMPTY
                    .withProviderId(providerId)
                    .withModel(model)
                    .inheritFrom(base);
        }
        // not pinned to any configured provider: keep the parent's provider, switch the model
        return base.withModel(ref);
    }
}
