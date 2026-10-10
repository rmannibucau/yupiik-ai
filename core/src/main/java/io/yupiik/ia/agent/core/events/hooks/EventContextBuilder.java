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
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.PermissionService;

/**
 * Assembles an {@link EventContext} from the live session state a producer holds at emission time.
 *
 * <p>An {@link ApplicationScoped} service: the process-scoped {@link CodingAgentConfiguration} (used for the
 * resolved project dir) is injected, so {@link #build} only takes the <em>session-scoped</em> state that cannot be
 * a bean — {@link PermissionService}, {@link ChatSettings} and the session coordinates. Any field whose source is
 * absent stays {@code null} on the wire.</p>
 */
@ApplicationScoped
public class EventContextBuilder {
    private final CodingAgentConfiguration configuration;

    public EventContextBuilder(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * @param permissions the session permission state; may be {@code null}.
     * @param settings    the session chat settings; may be {@code null}.
     * @param cwdOverride explicit working directory, wins over the configured project dir (eg. a bash workdir arg).
     */
    public EventContext build(
            final PermissionService permissions,
            final ChatSettings settings,
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final String cwdOverride) {
        return new EventContext(
                sessionId,
                subagentId,
                subagentName,
                cwdOverride != null && !cwdOverride.isBlank()
                        ? cwdOverride
                        : configuration == null
                                ? null
                                : configuration.resolveProjectDir().toString(),
                permissionMode(permissions),
                agentMode(permissions),
                settings == null ? null : settings.model(),
                settings == null ? null : settings.providerId());
    }

    private static String permissionMode(final PermissionService permissions) {
        if (permissions == null) {
            return null;
        }
        final var mode = permissions.permissionMode();
        return mode == null ? null : mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String agentMode(final PermissionService permissions) {
        if (permissions == null) {
            return null;
        }
        final var mode = permissions.mode();
        return mode == null ? null : mode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
