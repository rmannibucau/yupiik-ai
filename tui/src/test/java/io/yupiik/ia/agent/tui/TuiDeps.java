package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

/**
 * Test-only bean aggregating every {@link TuiCommand} dependency so tests do a
 * single lookup instead of one per dependency.
 */
@DefaultScoped
public record TuiDeps(TuiConfiguration tuiConfiguration,
                      CodingAgentConfiguration configuration,
                      AgentLoop agentLoop,
                      AgentEventBridge bridge,
                      PermissionService permissionService,
                      UserFeedbackService userFeedbackService,
                      ContextManager contextManager,
                      SessionStore sessionStore,
TodoStore todoStore,
                       PlanTodoSeeder planTodoSeeder,
                       PlanFileService planFileService,
                       ChatClient chatClient,
                      MemoryStore memoryStore,
                      SystemPromptBuilder systemPromptBuilder,
                      McpManager mcpManager,
                      VirtualThreadExecutor executor,
                      StructuralCodeGraph structuralCodeGraph,
                      ProviderManager providerManager,
                      AgentProvider agentProvider,
                      TuiTranscriptModel model) {
}
