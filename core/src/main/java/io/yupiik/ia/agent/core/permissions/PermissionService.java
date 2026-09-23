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
package io.yupiik.ia.agent.core.permissions;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PermissionService {
    private final CodingAgentConfiguration configuration;
    private volatile ApprovalGate gate;
    private final Set<String> sessionGrants = ConcurrentHashMap.newKeySet();
    private volatile AgentMode mode;
    private volatile PermissionMode permissionMode;
    private volatile boolean permitAll;

    public PermissionService(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected PermissionService() {
        this.configuration = null;
    }

    public void setGate(final ApprovalGate gate) {
        this.gate = gate;
    }

    public void setPermitAll(final boolean permitAll) {
        this.permitAll = permitAll;
    }

    public boolean isPermitAll() {
        return permitAll;
    }

    public void grantSession(final String toolName) {
        grantSession(toolName, Map.of());
    }

    public void grantSession(final String toolName, final Map<String, Object> args) {
        sessionGrants.add(grantKey(toolName, args));
    }

    /** Current session-scoped grants as human readable entries (project|tool:prefix), for the /permissions show command. */
    public List<String> sessionGrants() {
        return new ArrayList<>(sessionGrants);
    }

    public void setMode(final AgentMode mode) {
        this.mode = mode;
    }

    public AgentMode mode() {
        final var m = mode;
        if (m != null) {
            return m;
        }
        final var configured = configuration.mode();
        return configured != null ? configured : AgentMode.PLAN;
    }

    /**
     * Overrides the effective permission mode (ask/auto_edit/yolo) at runtime. Unlike the static
     * {@code yca.permissions.mode} config this lets entry points (TUI slash commands, /permissions)
     * switch the permission level that {@link #check} actually enforces, keeping the reported state in
     * sync with behaviour. A {@code null} resets to the configured default.
     */
    public void setPermissionMode(final PermissionMode permissionMode) {
        this.permissionMode = permissionMode;
    }

    /** The effective permission mode: the runtime override when set, otherwise the configured default (ASK). */
    public PermissionMode permissionMode() {
        final var override = permissionMode;
        if (override != null) {
            return override;
        }
        final var permissions = configuration.permissions();
        return permissions != null && permissions.mode() != null ? permissions.mode() : PermissionMode.ASK;
    }

    public record Decision(boolean allowed, String reason) {
        static Decision allow() {
            return new Decision(true, null);
        }

        static Decision deny(final String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision check(final String toolName, final ToolCategory category, final Map<String, Object> args) {
        return check(toolName, category, args, false);
    }

    public Decision check(
            final String toolName,
            final ToolCategory category,
            final Map<String, Object> args,
            final boolean readOnly) {
        final var permissions = configuration.permissions();
        if (permitAll) {
            return Decision.allow();
        }
        if (mode() == AgentMode.PLAN) {
            if (category == ToolCategory.READ || category == ToolCategory.AGENT) {
                return Decision.allow();
            }
            if (readOnly) {
                return Decision.allow();
            }
            if (category == ToolCategory.BASH
                    && args.get("command") instanceof final String command
                    && permissions.isPlanBash(command)) {
                return Decision.allow();
            }
            // plan mode stays read-only for what is auto-allowed above, but a
            // state-changing tool is not silently refused: it falls through to the
            // normal enforcement (deny/allow lists, session grants, permission mode
            // and, in ask mode, the interactive approval gate) so the user can choose.
        }
        if (permissions.isDenied(toolName)) {
            return Decision.deny("tool " + toolName + " is denied by configuration");
        }
        if (permissions.isAllowed(toolName)) {
            return Decision.allow();
        }
        if (category == ToolCategory.READ || category == ToolCategory.AGENT) {
            return Decision.allow();
        }
        if (matchesSessionGrant(toolName, args)) {
            return Decision.allow();
        }
        final var mode = permissionMode();
        if (mode == PermissionMode.YOLO) {
            return Decision.allow();
        }
        if (mode == PermissionMode.AUTO_EDIT && category == ToolCategory.EDIT) {
            return Decision.allow();
        }
        return approve(toolName, args);
    }

    private Decision approve(final String toolName, final Map<String, Object> args) {
        final var g = gate;
        if (g == null) {
            return Decision.deny(
                    "permission required but no interactive gate available (use yolo/auto_edit mode or run in the TUI)");
        }
        final var result = g.approve(describe(toolName, args), detail(toolName, args), toolName, args);
        return switch (result) {
            case ALLOW_ONCE -> Decision.allow();
            case ALLOW_SESSION -> {
                grantSession(toolName, args);
                yield Decision.allow();
            }
            default -> Decision.deny("refused by the user");
        };
    }

    private String grantKey(final String toolName, final Map<String, Object> args) {
        return projectDir() + "|" + toolName + ":" + prefixOf(args);
    }

    private boolean matchesSessionGrant(final String toolName, final Map<String, Object> args) {
        final var project = projectDir();
        final var requestedPrefix = prefixOf(args);
        for (final var grant : sessionGrants) {
            // format: <project>|<tool>:<prefix>
            final var sep = grant.indexOf('|');
            final var colon = grant.indexOf(':', sep + 1);
            if (sep <= 0 || colon < 0) {
                continue;
            }
            final var grantProject = grant.substring(0, sep);
            final var grantTool = grant.substring(sep + 1, colon);
            final var grantPrefix = grant.substring(colon + 1);
            if (!grantProject.equals(project) || !grantTool.equals(toolName)) {
                continue;
            }
            if (grantPrefix.isEmpty() || requestedPrefix.startsWith(grantPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static String prefixOf(final Map<String, Object> args) {
        if (args.get("command") instanceof final String command) {
            final var trimmed = command.strip();
            final var space = trimmed.indexOf(' ');
            return space < 0 ? trimmed : trimmed.substring(0, space);
        }
        if (args.get("path") instanceof final String path) {
            return path;
        }
        return "";
    }

    private String projectDir() {
        return configuration.resolveProjectDir().normalize().toString();
    }

    private static String describe(final String toolName, final Map<String, Object> args) {
        if (args.get("command") instanceof String c) {
            return "bash: " + c;
        }
        if (args.get("path") instanceof String p) {
            return toolName + ": " + p;
        }
        return toolName;
    }

    private static String detail(final String toolName, final Map<String, Object> args) {
        final var shownInTitle = new HashSet<String>();
        if (args.get("command") instanceof String) {
            shownInTitle.add("command");
        }
        if (args.get("path") instanceof String) {
            shownInTitle.add("path");
        }
        final var sb = new StringBuilder("Tool ").append(toolName).append('\n');
        for (final var e : args.entrySet()) {
            if (shownInTitle.contains(e.getKey())) {
                continue;
            }
            var v = String.valueOf(e.getValue());
            if (v.length() > 400) {
                v = v.substring(0, 400) + "...";
            }
            sb.append(e.getKey()).append(": ").append(v).append('\n');
        }
        return sb.toString();
    }
}
