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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.PermissionDeniedEvent;
import io.yupiik.ia.agent.core.events.PermissionRequestedEvent;
import io.yupiik.ia.agent.core.events.PermissionsChangedEvent;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PermissionService {
    private final CodingAgentConfiguration configuration;
    private final Emitter emitter;
    private final BashRulesRegistry bashRulesRegistry;
    /**
     * Session-scoped dynamic bash rule overrides (eg. families the user allowed for this session), cleared
     * when switching back to {@code PLAN} and when the session closes. Kept here (not in the
     * application-scoped {@link BashRulesRegistry}) because they follow the session lifecycle.
     */
    private final CopyOnWriteArrayList<CommandPermissionConfiguration> dynamicRules = new CopyOnWriteArrayList<>();

    private volatile ApprovalGate gate;
    private final Set<String> sessionGrants = ConcurrentHashMap.newKeySet();
    private volatile AgentMode mode;
    private volatile PermissionMode permissionMode;
    private volatile RulesCache rulesCache;
    /**
     * The session's resolved working/project directory, set once at session creation
     * ({@code SessionRuntimeFactory}); {@code null} means the process default
     * ({@code configuration.resolveProjectDir()}). Scope for the command allowlist hash.
     */
    private volatile java.nio.file.Path projectDir;

    public PermissionService(final CodingAgentConfiguration configuration) {
        this(configuration, null, null);
    }

    /** Set once when the session runtime is created so allowlist hashing scopes to the session's project. */
    public void projectDir(final java.nio.file.Path dir) {
        this.projectDir = dir == null ? null : dir.toAbsolutePath().normalize();
    }

    public PermissionService(
            final CodingAgentConfiguration configuration, final io.yupiik.fusion.framework.api.event.Emitter emitter) {
        this(configuration, emitter, null);
    }

    /** Session services created by the runtime factory pass the shared registry; tests/transient usages pass null. */
    public PermissionService(
            final CodingAgentConfiguration configuration,
            final io.yupiik.fusion.framework.api.event.Emitter emitter,
            final BashRulesRegistry bashRulesRegistry) {
        this.configuration = configuration;
        this.emitter = emitter;
        this.bashRulesRegistry = bashRulesRegistry;
    }

    protected PermissionService() {
        this.configuration = null;
        this.emitter = null;
        this.bashRulesRegistry = null;
    }

    /**
     * Adds a dynamic bash rule override for this session (deduplicated with the existing ones). The rule
     * participates in the read-only evaluation (and therefore in {@code $( ... )} substitution verification)
     * until {@link #clearDynamicRules()} - ie. until the next switch back to plan or the session close.
     */
    public void addDynamicRule(final CommandPermissionConfiguration rule) {
        if (rule != null && !dynamicRules.contains(rule)) {
            dynamicRules.add(rule);
        }
    }

    /** Removes a previously added dynamic rule; no-op when absent. */
    public void removeDynamicRule(final CommandPermissionConfiguration rule) {
        dynamicRules.remove(rule);
    }

    /** Snapshot of the session's dynamic bash rule overrides. */
    public List<CommandPermissionConfiguration> dynamicRules() {
        return new ArrayList<>(dynamicRules);
    }

    /** Drops every dynamic bash rule override of this session (switch back to plan, session release). */
    public void clearDynamicRules() {
        dynamicRules.clear();
    }

    public void setGate(final ApprovalGate gate) {
        this.gate = gate;
    }

    /** The approval gate currently installed, if any (used to route /btw approvals to the same gate as the main turn). */
    public ApprovalGate gate() {
        return gate;
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

    /**
     * Drops every session-scoped grant. Mode switches back to {@code PLAN} call this: approvals
     * granted during a build phase must not leak into the next planning phase.
     */
    public void clearSessionGrants() {
        sessionGrants.clear();
    }

    /**
     * Creates child-scoped permission state for a delegated agent run. Effective modes, project scope,
     * dynamic bash rules, and the approval transport are inherited; session grants are deliberately not
     * copied, and any approval granted to the child is recorded only on the returned instance.
     */
    public PermissionService forkForChild() {
        final var child = new PermissionService(configuration, emitter, bashRulesRegistry);
        child.projectDir(projectDir);
        child.mode = mode();
        child.permissionMode = permissionMode();
        child.gate = gate;
        dynamicRules.forEach(child::addDynamicRule);
        return child;
    }

    public void setMode(final AgentMode mode) {
        final var from = this.mode;
        this.mode = mode;
        emitPermissionsChanged("agent", from == null ? null : from.name(), mode == null ? null : mode.name());
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
        final var from = this.permissionMode;
        this.permissionMode = permissionMode;
        emitPermissionsChanged(
                "permission", from == null ? null : from.name(), permissionMode == null ? null : permissionMode.name());
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

    /** Observe-only notification of a permission or agent mode change. */
    private void emitPermissionsChanged(final String kind, final String from, final String to) {
        if (emitter != null) {
            emitter.emit(new PermissionsChangedEvent(context(), kind, from, to));
        }
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
        final var command =
                category == ToolCategory.BASH && args.get("command") instanceof final String c && !c.isBlank()
                        ? c
                        : null;
        // the deny list is authoritative in every mode, including for bash commands
        // where the patterns are matched segment by segment
        if (permissions != null) {
            if (permissions.isDenied(toolName)) {
                return Decision.deny("tool " + toolName + " is denied by configuration");
            }
            if (command != null) {
                for (final var segment : compiledBashRules().segments(command)) {
                    if (permissions.isDenied(segment) || permissions.isDenied(toolName + ':' + segment)) {
                        return Decision.deny("command is denied by configuration: " + segment);
                    }
                }
            }
        }
        if (mode() == AgentMode.PLAN && (category == ToolCategory.READ || category == ToolCategory.AGENT || readOnly)) {
            return Decision.allow();
        }
        if (permissions != null) {
            if (permissions.isAllowed(toolName)) {
                return Decision.allow();
            }
            if (command != null) {
                // command level allow patterns apply per segment: every segment must be allowed
                final var segments = compiledBashRules().segments(command);
                if (!segments.isEmpty()) {
                    var allAllowed = true;
                    for (final var segment : segments) {
                        if (!permissions.isAllowed(segment) && !permissions.isAllowed(toolName + ':' + segment)) {
                            allAllowed = false;
                            break;
                        }
                    }
                    if (allAllowed) {
                        return Decision.allow();
                    }
                }
            }
        }
        if (category == ToolCategory.READ || category == ToolCategory.AGENT) {
            return Decision.allow();
        }
        if (matchesSessionGrant(toolName, args)) {
            return Decision.allow();
        }
        final var permission = permissionMode();
        if (permission == PermissionMode.YOLO) {
            return Decision.allow();
        }
        final var asked = command != null ? compiledBashRules().evaluate(mode(), command) : null;
        if (command != null && asked != null && asked.allowed()) {
            return Decision.allow();
        }
        if (permission == PermissionMode.AUTO_EDIT && category == ToolCategory.EDIT) {
            return Decision.allow();
        }
        return approve(toolName, args, asked);
    }

    private Decision approve(final String toolName, final Map<String, Object> args, final BashRules.Evaluation asked) {
        final var g = gate;
        if (g == null) {
            final var base =
                    "permission denied: mode is 'ask' (the default) and this run has no interactive approval wired - use the TUI, or set yca.permissions.mode=yolo/auto_edit to allow unattended writes";
            final var reason = bashCause(asked) == null ? base : base + " (" + bashCause(asked) + ')';
            emitDenied(toolName, args, reason);
            return Decision.deny(reason);
        }
        final var detail = detail(toolName, args, asked);
        // blockable hook gate: a forbid denies before the interactive modal is shown
        if (emitter != null) {
            final var hook = new HookResult();
            emitter.emit(new PermissionRequestedEvent(context(), toolName, args, detail, hook));
            if (hook.forbidden()) {
                final var reason = hook.reason() == null ? "forbidden by hook" : hook.reason();
                emitDenied(toolName, args, reason);
                return Decision.deny(reason);
            }
        }
        final var result = g.approve(describe(toolName, args), detail, toolName, args);
        return switch (result) {
            case ALLOW_ONCE -> Decision.allow();
            case ALLOW_SESSION -> {
                grantSession(toolName, args);
                yield Decision.allow();
            }
            default -> {
                final var reason = "refused by the user" + (bashCause(asked) == null ? "" : ": " + bashCause(asked));
                emitDenied(toolName, args, reason);
                yield Decision.deny(reason);
            }
        };
    }

    /** Observe-only notification of a denied permission request. */
    private void emitDenied(final String toolName, final Map<String, Object> args, final String reason) {
        if (emitter != null) {
            emitter.emit(new PermissionDeniedEvent(context(), toolName, args, reason));
        }
    }

    /** Shared, per-process event context: no session, cwd and current modes only. */
    private EventContext context() {
        return new EventContext(null, null, null, projectDir(), permissionMode().name(), mode().name(), null, null);
    }

    /**
     * The human-readable reason a bash command reached the approval gate, or {@code null} when no
     * specific segment explains it (non-bash tools). The gate flow itself is untouched: the cause only
     * enriches the modal detail and the denial feedback the model sees.
     */
    private static String bashCause(final BashRules.Evaluation asked) {
        if (asked == null || asked.allowed() || asked.deniedSegment() == null) {
            return null;
        }
        final var when =
                switch (asked.deniedCause()) {
                    case NO_MATCH -> "is not in the read-only bash families (yca.permissions.bash.rules)";
                    case NOT_YOLO -> "matches a bash rule that requires interactive approval in this mode";
                    case REDIRECT_OR_SUBSTITUTION -> "carries a redirection or command substitution";
                };
        return "segment '" + asked.deniedSegment() + "' " + when;
    }

    private String grantKey(final String toolName, final Map<String, Object> args) {
        return projectDir() + "|" + toolName + ":" + commandPrefixOf(args);
    }

    private record RulesCache(BashPermissionsConfiguration source, BashRules.Compiled compiled) {}

    /** Lazily compiled {@code yca.permissions.bash.rules}, refreshed if the underlying configuration changes. */
    private BashRules.Compiled compiledBashRules() {
        final var registry = bashRulesRegistry;
        if (registry != null) {
            // the registry owns the app-scoped caches: base config compiled once per config identity,
            // plus a bounded combined cache keyed by this session's dynamic overrides snapshot
            return registry.compiled(dynamicRules);
        }
        final var permissions = configuration.permissions();
        final var bash = permissions != null ? permissions.bash() : null;
        final var cache = rulesCache;
        if (cache != null && cache.source() == bash) {
            return cache.compiled();
        }
        final var compiled = BashRules.compile(bash);
        rulesCache = new RulesCache(bash, compiled);
        return compiled;
    }

    /**
     * Exploration-coverage keys of a bash command for the plateau detector, derived from the
     * configured read-only bash rules ({@code yca.permissions.bash.rules}): the matched rule
     * {@code commandStart} plus the segment target. Commands not covered by any rule (or not
     * exploration-shaped) yield no key, so they never trip the plateau guard. Uses the same
     * quote-aware tokenizer and rule matching as {@link #check(String, ToolCategory, Map)}.
     */
    public List<String> bashCoverage(final String command) {
        return compiledBashRules().coverageKeys(command);
    }

    private boolean matchesSessionGrant(final String toolName, final Map<String, Object> args) {
        final var project = projectDir();
        final var requestedPrefix = commandPrefixOf(args);
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

    /**
     * Command prefix used to key session grants. Bash commands matched by a {@code yca.permissions.bash.rules}
     * entry use the rule {@code commandStart} tokens (opencode arity style: approving {@code docker run x}
     * grants {@code docker run}, not {@code docker}); anything else falls back to the first word.
     */
    private String commandPrefixOf(final Map<String, Object> args) {
        if (args.get("command") instanceof final String command) {
            final var prefix = compiledBashRules().evaluate(mode(), command).prefix();
            if (prefix != null) {
                return prefix;
            }
        }
        return prefixOf(args);
    }

    private String projectDir() {
        return (projectDir != null
                        ? projectDir
                        : configuration.resolveProjectDir().normalize())
                .toString();
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

    private static String detail(
            final String toolName, final Map<String, Object> args, final BashRules.Evaluation asked) {
        final var shownInTitle = new HashSet<String>();
        if (args.get("command") instanceof String) {
            shownInTitle.add("command");
        }
        if (args.get("path") instanceof String) {
            shownInTitle.add("path");
        }
        final var sb = new StringBuilder("Tool ").append(toolName).append('\n');
        final var cause = bashCause(asked);
        if (cause != null) {
            sb.append("Why asked: ").append(cause).append('\n');
        }
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
