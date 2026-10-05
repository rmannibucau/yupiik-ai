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
package io.yupiik.ia.agent.core.tools;

import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.todo.TodoStore;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The per-session services a tool may need, passed explicitly down the call
 * chain (never looked up from a thread-local). Tools execute synchronously on
 * the turn thread, so the context the {@code AgentLoop} was created with is
 * simply forwarded to
 * {@link EmbeddedTool#execute(java.util.Map, ToolContext)}.
 *
 * <p>Stateless tools (file access, shell, code graph, ...) ignore it entirely
 * and only override {@link EmbeddedTool#execute(java.util.Map)}.</p>
 *
 * @param sessionId      the session (tab) owning the running turn
 * @param subagentId     the subagent id when the turn runs in a subagent, else null
 * @param subagentName   the subagent display name when in a subagent, else null
 * @param permissions    the asking session's permission state (its own mode)
 * @param feedback       the asking session's feedback service (its gate renders in that tab)
 * @param todos          the asking session's todo list
 * @param statusReporter the asking session's status reporter (optimization notices land in that tab)
 * @param usage          the asking session's usage tracker (subagent usage merges into it)
 * @param contextView    the asking session's context view (status/compact requests route to that tab)
 * @param cancelled      cancellation probe of the running turn; a tool may poll it and stop its
 *                       work early (killing a subprocess for example). Never {@code null}: a
 *                       context no turn owns passes {@link Cancellations#NEVER}.
 */
public record ToolContext(
        String sessionId,
        String subagentId,
        String subagentName,
        PermissionService permissions,
        UserFeedbackService feedback,
        TodoStore todos,
        StatusReporter statusReporter,
        UsageTracker usage,
        ContextView contextView,
        BooleanSupplier cancelled) {

    public ToolContext {
        // a probe is always a real supplier: polling a null one used to surface as an
        // "unexpected failure" NullPointerException in the middle of a turn
        cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    /** A context without any session-scoped service, for standalone tool invocation. */
    public static ToolContext empty() {
        return new ToolContext(null, null, null, null, null, null, null, null, null, Cancellations.NEVER);
    }

    /** Cancellation probe, never {@code null}: standalone contexts report "not cancelled". */
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }
}
