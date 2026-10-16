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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.agent.TodoPromptResolver;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.ContextWindowCache;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.SessionStartedEvent;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.BashRulesRegistry;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.concurrent.ExecutorService;

/**
 * Creates one {@link SessionRuntime} per session (one TUI tab, the CLI session):
 * the single place where the process scoped beans and the session scoped {@code new}
 * instances are assembled. No session state is injected from the container.
 */
@ApplicationScoped
public class SessionRuntimeFactory {
    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final CodingAgentConfiguration configuration;
    private final ContextWindowCache contextWindowCache;
    private final SessionStore sessionStore;
    private final MemoryStore memoryStore;
    private final AgentProvider agentProvider;
    private final ProviderManager providerManager;
    private final TodoPromptResolver todoPromptResolver;
    private final MavenRepositoryKnowledgeService mavenRepositoryKnowledge;
    private final StructuralCodeGraph structuralCodeGraph;
    private final SnapshotStore snapshotStore;
    private final JsonMapper mapper;
    private final Emitter emitter;
    private final Clock clock;
    private final PrecisionDurationMarkerProvider precisionDurationMarker;
    private final ExecutorService executor;
    private final BashRulesRegistry bashRulesRegistry;
    private final MemoryProductionPolicy memoryProductionPolicy;
    private volatile io.yupiik.ia.agent.core.security.SecretRedactor secretRedactor;

    public SessionRuntimeFactory(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final ContextWindowCache contextWindowCache,
            final SessionStore sessionStore,
            final MemoryStore memoryStore,
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final TodoPromptResolver todoPromptResolver,
            final MavenRepositoryKnowledgeService mavenRepositoryKnowledge,
            final StructuralCodeGraph structuralCodeGraph,
            final SnapshotStore snapshotStore,
            final JsonMapper mapper,
            final Emitter emitter,
            final Clock clock,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final ExecutorService executor,
            final BashRulesRegistry bashRulesRegistry,
            final MemoryProductionPolicy memoryProductionPolicy,
            final io.yupiik.ia.agent.core.security.SecretRedactor secretRedactor) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.contextWindowCache = contextWindowCache;
        this.sessionStore = sessionStore;
        this.memoryStore = memoryStore;
        this.agentProvider = agentProvider;
        this.providerManager = providerManager;
        this.todoPromptResolver = todoPromptResolver;
        this.mavenRepositoryKnowledge = mavenRepositoryKnowledge;
        this.structuralCodeGraph = structuralCodeGraph;
        this.snapshotStore = snapshotStore;
        this.mapper = mapper;
        this.emitter = emitter;
        this.clock = clock;
        this.precisionDurationMarker = precisionDurationMarker;
        this.executor = executor;
        this.bashRulesRegistry = bashRulesRegistry;
        this.memoryProductionPolicy = memoryProductionPolicy;
        this.secretRedactor = secretRedactor;
    }

    /**
     * @param sessionKey the stable per-tab key (todos file + event routing);
     *                   usually the session id at tab creation
     * @param sessionId  the initial chat session id owned by the runtime
     */
    public SessionRuntime create(final String sessionKey, final String sessionId) {
        final var permissions = new PermissionService(configuration, emitter, bashRulesRegistry);
        final var feedback = new UserFeedbackService();
        final var usage = new UsageTracker(providerManager, chatClient, contextWindowCache, clock);
        final var todos = new TodoStore(configuration, mapper, sessionKey);
        todos.load();
        final var status = new StatusReporter();
        final var contextView = new ContextView();
        final var compactionCoordinator =
                new CompactionCoordinator(configuration, chatClient, usage, providerManager, emitter, sessionId);
        final var contextManager = new ContextManager(configuration, usage, compactionCoordinator);
        final var memoryCompactor =
                new MemoryCompactor(chatClient, configuration, memoryStore, providerManager, usage, emitter, sessionId);
        final var memoryExtractor = new MemoryExtractor(
                chatClient,
                configuration,
                providerManager,
                memoryStore,
                mapper,
                executor,
                emitter,
                clock,
                usage,
                sessionId,
                memoryCompactor,
                memoryProductionPolicy,
                secretRedactor);
        final var systemPromptBuilder = new SystemPromptBuilder(
                configuration,
                memoryStore,
                todos,
                permissions,
                agentProvider,
                providerManager,
                todoPromptResolver,
                mavenRepositoryKnowledge,
                secretRedactor);
        final var loop = new AgentLoop(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissions,
                usage,
                contextManager,
                contextView,
                sessionStore,
                systemPromptBuilder,
                memoryExtractor,
                status,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                snapshotStore,
                todos,
                executor,
                sessionId,
                null,
                null,
                feedback,
                precisionDurationMarker);
        // resolve the session's working directory once (explicit override > recorded project marker >
        // process default) and freeze it in the loop + system prompt builder: the stable system-prompt
        // core must stay byte-identical across turns, so this is never re-read mid-conversation.
        final var workdir = resolveSessionWorkdir(sessionId);
        if (workdir != null) {
            loop.sessionWorkdir(workdir);
            systemPromptBuilder.workdir(workdir);
            permissions.projectDir(workdir);
        }
        if (emitter != null) {
            emitter.emit(new SessionStartedEvent(
                    new EventContext(
                            null, null, null, configuration.resolveProjectDir().toString(), null, null, null, null),
                    sessionId,
                    sessionKey));
        }
        return new SessionRuntime(
                loop,
                permissions,
                feedback,
                usage,
                todos,
                status,
                contextManager,
                systemPromptBuilder,
                memoryProductionPolicy,
                sessionId);
    }

    /** Convenience: the key and the initial session id are the same value. */
    public SessionRuntime create(final String sessionId) {
        return create(sessionId, sessionId);
    }

    /**
     * Resolves a session's working directory to a canonical absolute {@link Path}, or {@code null}
     * when none can be established (the process default then applies downstream). Prefers the recorded
     * project marker so a persisted session survives restarts on its own root, but only while that
     * marker still points at an existing directory: a stale marker (project moved/renamed/deleted since
     * the session was recorded) must never hand a dead path to tools, so it falls back to the configured
     * working directory instead.
     */
    protected java.nio.file.Path resolveSessionWorkdir(final String sessionId) {
        if (sessionStore == null || sessionId == null) {
            return null;
        }
        final var recorded = sessionStore.projectDirOf(sessionId);
        if (recorded != null && !recorded.isBlank()) {
            try {
                final var dir = java.nio.file.Path.of(recorded).toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(dir)) {
                    return dir;
                }
            } catch (final RuntimeException e) {
                // malformed recorded marker: fall through to the configured working directory
            }
        }
        // no record, a malformed one, or a stale one that no longer exists on disk: use the configured
        // working directory so the session still gets a live cwd (never error just because the marker is dead).
        return configuration == null ? null : configuration.resolveWorkingDir();
    }

    /** For subclasses wiring a fake session (tests). */
    protected SessionRuntimeFactory() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.contextWindowCache = null;
        this.sessionStore = null;
        this.memoryStore = null;
        this.agentProvider = null;
        this.providerManager = null;
        this.todoPromptResolver = null;
        this.mavenRepositoryKnowledge = null;
        this.structuralCodeGraph = null;
        this.snapshotStore = null;
        this.mapper = null;
        this.emitter = null;
        this.clock = null;
        this.executor = null;
        this.precisionDurationMarker = null;
        this.bashRulesRegistry = null;
        this.memoryProductionPolicy = null;
        this.secretRedactor = null;
    }
}
