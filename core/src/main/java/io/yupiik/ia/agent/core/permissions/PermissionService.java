package io.yupiik.ia.agent.core.permissions;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.tools.ToolCategory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PermissionService {
    private final CodingAgentConfiguration configuration;
    private volatile ApprovalGate gate;
    private final Set<String> sessionGrants = ConcurrentHashMap.newKeySet();
    private volatile AgentMode mode;
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
        sessionGrants.add(toolName);
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
        return configured != null ? configured : AgentMode.BUILD;
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

    public Decision check(final String toolName, final ToolCategory category, final Map<String, Object> args,
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
            return Decision.deny("plan mode allows read-only tools only");
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
        if (sessionGrants.contains(toolName)) {
            return Decision.allow();
        }
        final var mode = permissions.mode();
        if (mode == PermissionMode.YOLO) {
            return Decision.allow();
        }
        if (mode == PermissionMode.AUTO_EDIT && category == ToolCategory.EDIT) {
            return Decision.allow();
        }
        final var g = gate;
        if (g == null) {
            return Decision.deny("permission required but no interactive gate available (use yolo/auto_edit mode or run in the TUI)");
        }
        return g.approve(describe(toolName, args), detail(toolName, args))
                ? Decision.allow() : Decision.deny("refused by the user");
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
        final var sb = new StringBuilder("Tool ").append(toolName).append('\n');
        for (final var e : args.entrySet()) {
            var v = String.valueOf(e.getValue());
            if (v.length() > 400) {
                v = v.substring(0, 400) + "...";
            }
            sb.append(e.getKey()).append(": ").append(v).append('\n');
        }
        return sb.toString();
    }
}
