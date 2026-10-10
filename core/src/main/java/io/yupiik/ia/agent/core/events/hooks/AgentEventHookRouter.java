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
package io.yupiik.ia.agent.core.events.hooks;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.events.CompactionDoneEvent;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.NotificationEvent;
import io.yupiik.ia.agent.core.events.SubagentStartedEvent;
import io.yupiik.ia.agent.core.events.SubagentStoppedEvent;
import io.yupiik.ia.agent.core.events.ToolUsePostEvent;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;

/**
 * Routes existing bus events (which already carry session coordinates) into the hook pipeline as {@link
 * io.yupiik.ia.agent.core.events.YcaEvent} wrappers with an enriched {@link EventContext}. Observe-only tier: the
 * wrapped events (post-tool, subagent start/stop, compaction done, notification) cannot be vetoed — {@code
 * yca.hooks.<Id>} callbacks that print a {@code HookResponse} ack only get a cosmetic {@code context}.
 *
 * <p>The {@link EventContext} is built from the bus record's session coordinates plus the configured project dir
 * ({@code cwd}); {@code permissionMode}/{@code agentMode}/{@code model} stay {@code null} because those require the
 * per-session state a bus record does not carry.</p>
 */
@ApplicationScoped
public class AgentEventHookRouter {
    private final CallbackRunner runner;
    private final EventContextBuilder contextBuilder;

    public AgentEventHookRouter(final CallbackRunner runner, final EventContextBuilder contextBuilder) {
        this.runner = runner;
        this.contextBuilder = contextBuilder;
    }

    public void onToolUsePost(@OnEvent final AfterToolExecutionEvent event) {
        final var context = context(event.sessionId(), event.subagentId(), event.subagentName());
        if (context == null) {
            return;
        }
        runner.run(
                "ToolUsePostEvent",
                new ToolUsePostEvent(
                                context,
                                event.tool(),
                                null,
                                null,
                                event.success(),
                                event.output(),
                                event.success() ? null : event.output())
                        .payload(),
                0);
    }

    public void onSubagentStarted(@OnEvent final AgentEvent.SubagentStarted event) {
        final var context = context(event.sessionId(), event.subagentId(), event.subagentName());
        if (context == null) {
            return;
        }
        runner.run(
                "SubagentStarted",
                new SubagentStartedEvent(context, event.task(), event.subagentId(), event.subagentName()).payload(),
                0);
    }

    public void onSubagentStopped(@OnEvent final AgentEvent.SubagentCompleted event) {
        final var context = context(event.sessionId(), event.subagentId(), event.subagentName());
        if (context == null) {
            return;
        }
        runner.run(
                "SubagentStopped",
                new SubagentStoppedEvent(
                                context,
                                event.summary(),
                                event.success(),
                                event.cancelled(),
                                event.subagentId(),
                                event.subagentName())
                        .payload(),
                0);
    }

    public void onCompactionDone(@OnEvent final AgentEvent.Compacted event) {
        final var context = context(event.sessionId(), event.subagentId(), event.subagentName());
        if (context == null) {
            return;
        }
        runner.run("CompactionDone", new CompactionDoneEvent(context, event.kind(), event.mode()).payload(), 0);
    }

    public void onNotice(@OnEvent final AgentEvent.Notice event) {
        final var context = context(event.sessionId(), event.subagentId(), event.subagentName());
        if (context == null) {
            return;
        }
        runner.run(
                "Notification",
                new NotificationEvent(
                                context,
                                event.level().name().toLowerCase(java.util.Locale.ROOT),
                                event.message(),
                                event.finishReason())
                        .payload(),
                0);
    }

    private EventContext context(final String sessionId, final String subagentId, final String subagentName) {
        if (contextBuilder == null) {
            return new EventContext(sessionId, subagentId, subagentName, null, null, null, null, null);
        }
        return contextBuilder.build(null, null, sessionId, subagentId, subagentName, null);
    }
}
