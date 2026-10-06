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

import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.todo.TodoStore;

/**
 * Everything a single session (one TUI tab, the CLI session, a sub-agent) owns:
 * its permission state, feedback gate, usage accounting, todo list, status
 * reporter and agent loop.
 *
 * <p>Instances are created with {@code new} (typically by
 * {@link SessionRuntimeFactory}) and passed by reference - never fetched from a
 * thread-local - so concurrent tabs keep independent state while the process
 * scoped services (chat client, providers, tool registry, session store) stay
 * shared on purpose: they own the single LLM concurrency/rate-limit budget.</p>
 */
public final class SessionRuntime {
    private final AgentLoop loop;
    private final PermissionService permissions;
    private final UserFeedbackService feedback;
    private final UsageTracker usage;
    private final TodoStore todos;
    private final StatusReporter status;
    private final ContextManager contextManager;
    private final SystemPromptBuilder systemPromptBuilder;

    public SessionRuntime(
            final AgentLoop loop,
            final PermissionService permissions,
            final UserFeedbackService feedback,
            final UsageTracker usage,
            final TodoStore todos,
            final StatusReporter status,
            final ContextManager contextManager,
            final SystemPromptBuilder systemPromptBuilder) {
        this.loop = loop;
        this.permissions = permissions;
        this.feedback = feedback;
        this.usage = usage;
        this.todos = todos;
        this.status = status;
        this.contextManager = contextManager;
        this.systemPromptBuilder = systemPromptBuilder;
    }

    /** The session's own agent loop (turn execution, per-turn metrics). */
    public AgentLoop loop() {
        return loop;
    }

    /** The session's permission state: mode and approval gate. */
    public PermissionService permissions() {
        return permissions;
    }

    /** The session's feedback gate, rendering its questions in its own tab. */
    public UserFeedbackService feedback() {
        return feedback;
    }

    /** The session's usage accounting (context anchor, token counters). */
    public UsageTracker usage() {
        return usage;
    }

    /** The session's todo list, persisted under its own key. */
    public TodoStore todos() {
        return todos;
    }

    /** The session's status reporter (optimization notices, indexing progress). */
    public StatusReporter status() {
        return status;
    }

    /** The session's context manager (compaction, context budget). */
    public ContextManager contextManager() {
        return contextManager;
    }

    /** The session's system prompt builder (reflects this session's mode and todos). */
    public SystemPromptBuilder systemPromptBuilder() {
        return systemPromptBuilder;
    }

    /** The session's per-tab provider/model selection (owned by the agent loop). */
    public ChatSettings chatSettings() {
        return loop.chatSettings();
    }

    /** Applies a new session selection (mid-turn changes take effect from the next round/tool call). */
    public void updateChatSettings(final ChatSettings value) {
        loop.updateChatSettings(value);
    }
}
