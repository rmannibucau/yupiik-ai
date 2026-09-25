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
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;

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
    private final VirtualThreadExecutor executor;

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
            final VirtualThreadExecutor executor) {
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
        this.executor = executor;
    }

    /**
     * @param sessionKey the stable per-tab key (todos file + event routing);
     *                   usually the session id at tab creation
     * @param sessionId  the initial chat session id owned by the runtime
     */
    public SessionRuntime create(final String sessionKey, final String sessionId) {
        final var permissions = new PermissionService(configuration);
        final var feedback = new UserFeedbackService();
        final var usage = new UsageTracker(providerManager, chatClient, contextWindowCache, clock);
        final var todos = new TodoStore(configuration, mapper, sessionKey);
        todos.load();
        final var status = new StatusReporter();
        final var contextView = new ContextView();
        final var compactionCoordinator =
                new CompactionCoordinator(configuration, chatClient, usage, providerManager, emitter, sessionId);
        final var contextManager = new ContextManager(configuration, usage, compactionCoordinator);
        final var memoryExtractor = new MemoryExtractor(
                chatClient, configuration, memoryStore, mapper, executor, emitter, clock, usage, sessionId);
        final var systemPromptBuilder = new SystemPromptBuilder(
                configuration,
                memoryStore,
                todos,
                permissions,
                agentProvider,
                providerManager,
                todoPromptResolver,
                mavenRepositoryKnowledge);
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
                feedback);
        return new SessionRuntime(
                loop, permissions, feedback, usage, todos, status, contextManager, systemPromptBuilder);
    }

    /** Convenience: the key and the initial session id are the same value. */
    public SessionRuntime create(final String sessionId) {
        return create(sessionId, sessionId);
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
    }
}
