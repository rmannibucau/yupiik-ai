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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Executes a single tool call (lookup, argument parsing, pre-tool gate, execution and diagnostic
 * normalization). Owns the running-tool cancellation registry and the per-session ordinal counter.
 * Conversation mutation and tool-result landing stay in {@link AgentLoop}; parallel batching and
 * coverage/path computation are part of the outcome produced here.
 */
public class ToolCallExecution {
    private static final Logger LOGGER = Logger.getLogger(ToolCallExecution.class.getName());

    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final PermissionService permissionService;
    private final JsonMapper mapper;
    private final Emitter emitter;
    private final PrecisionDurationMarkerProvider precisionDurationMarker;
    private final CodingAgentConfiguration configuration;
    private final UserFeedbackService userFeedbackService;
    private final TodoStore todoStore;
    private final StatusReporter statusReporter;
    private final UsageTracker usageTracker;
    private final ContextView contextView;

    private final Supplier<String> currentSessionId;
    private final Supplier<String> subagentId;
    private final Supplier<String> subagentName;
    private final Supplier<Path> sessionWorkdir;
    private final Supplier<SubagentBudget> subagentBudget;
    private final Supplier<Set<String>> effectiveAllowedTools;
    private final Supplier<Set<String>> servedToolNames;
    private final Supplier<ChatSettings> chatSettings;
    private final BooleanSupplier sessionClosed;

    private final ConcurrentHashMap<Long, AtomicBoolean> runningToolCancels = new ConcurrentHashMap<>();
    private final AtomicLong toolCallOrdinal = new AtomicLong();

    public ToolCallExecution(
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final PermissionService permissionService,
            final JsonMapper mapper,
            final Emitter emitter,
            final PrecisionDurationMarkerProvider precisionDurationMarker,
            final CodingAgentConfiguration configuration,
            final UserFeedbackService userFeedbackService,
            final TodoStore todoStore,
            final StatusReporter statusReporter,
            final UsageTracker usageTracker,
            final ContextView contextView,
            final Supplier<String> currentSessionId,
            final Supplier<String> subagentId,
            final Supplier<String> subagentName,
            final Supplier<Path> sessionWorkdir,
            final Supplier<SubagentBudget> subagentBudget,
            final Supplier<Set<String>> effectiveAllowedTools,
            final Supplier<Set<String>> servedToolNames,
            final Supplier<ChatSettings> chatSettings,
            final BooleanSupplier sessionClosed) {
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.permissionService = permissionService;
        this.mapper = mapper;
        this.emitter = emitter;
        this.precisionDurationMarker = precisionDurationMarker;
        this.configuration = configuration;
        this.userFeedbackService = userFeedbackService;
        this.todoStore = todoStore;
        this.statusReporter = statusReporter;
        this.usageTracker = usageTracker;
        this.contextView = contextView;
        this.currentSessionId = currentSessionId;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
        this.sessionWorkdir = sessionWorkdir;
        this.subagentBudget = subagentBudget;
        this.effectiveAllowedTools = effectiveAllowedTools;
        this.servedToolNames = servedToolNames;
        this.chatSettings = chatSettings;
        this.sessionClosed = sessionClosed;
    }

    /**
     * Cancels the tool call with the given ordinal (the TUI's #{@code n} card): flips its per-call
     * probe so the running tool aborts on its next cancellation poll. Does nothing when the call
     * already finished. The turn itself keeps running - the aborting tool result is fed back to the
     * model like any other failure.
     */
    public void cancelTool(final long ordinal) {
        final var handle = runningToolCancels.get(ordinal);
        if (handle != null) {
            handle.set(true);
        }
    }

    /** Whether the tool call with the given ordinal is still running. */
    public boolean isToolRunning(final long ordinal) {
        return runningToolCancels.containsKey(ordinal);
    }

    private void registerRunningTool(final long ordinal, final AtomicBoolean cancel) {
        runningToolCancels.put(ordinal, cancel);
    }

    private void unregisterRunningTool(final long ordinal) {
        if (ordinal > 0) {
            runningToolCancels.remove(ordinal);
        }
    }

    /** Whether a call targets a read-only tool (never state-changing). Reads are owned by the plateau guard. */
    public boolean isReadOnly(final ToolCall call) {
        if (toolRegistry == null) {
            return false;
        }
        final var fn = call.function();
        final EmbeddedTool tool = toolRegistry.tool(fn == null || fn.name() == null ? "" : fn.name());
        return tool != null && (tool.readonly() || tool.category() == ToolCategory.READ);
    }

    /** A call can join a parallel run only if its tool is known and cannot mutate state. */
    public boolean isParallelizable(final ToolCall call) {
        return isReadOnly(call);
    }

    public boolean parallelReadonlyEnabled() {
        if (configuration == null || configuration.tools() == null) {
            return true; // conservative default: the feature is on unless explicitly disabled
        }
        return configuration.tools().parallelReadonly();
    }

    /** Unwraps a parallel tool future, rethrowing the cause as the loop would. */
    public ToolOutcome awaitTool(final Future<ToolOutcome> future) throws InterruptedException {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("parallel tool execution failed", cause);
        }
    }

    /** Runs one tool call (lookup, arguments parsing, permission gate, execution) without touching the conversation. */
    public ToolOutcome runTool(final ToolCall call, final BooleanSupplier cancelled, final ChatSettings settings) {
        final var name = call.function() == null || call.function().name() == null
                ? ""
                : call.function().name();
        final var argsJson = call.function() == null || call.function().arguments() == null
                ? "{}"
                : call.function().arguments();
        final var callCancel = new AtomicBoolean();
        final var ordinal = toolCallOrdinal.incrementAndGet();
        registerRunningTool(ordinal, callCancel);
        final EmbeddedTool tool = toolRegistry.tool(name);
        if (tool == null) {
            final var diagnostic = "Unknown tool: " + name;
            unregisterRunningTool(ordinal);
            emit(new AgentEvent.ToolFinished(
                    name,
                    unknownToolDiagnostic(name),
                    false,
                    false,
                    ordinal,
                    currentSessionId.get(),
                    subagentId.get(),
                    subagentName.get(),
                    sessionWorkdir.get()));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        final var childToolCeiling = effectiveAllowedTools.get();
        if (childToolCeiling != null && !childToolCeiling.contains(name)) {
            final var diagnostic = "Tool is not allowed for this agent run: " + name;
            unregisterRunningTool(ordinal);
            emit(new AgentEvent.ToolFinished(
                    name,
                    diagnostic,
                    false,
                    false,
                    ordinal,
                    currentSessionId.get(),
                    subagentId.get(),
                    subagentName.get(),
                    sessionWorkdir.get()));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        // a tool that is served but was NOT offered in the current request's wire list must not be executed:
        // plan mode removes the EDIT tools from the schema, so a model reaching for one (e.g. carried over
        // from a prior build-mode turn in the append-only transcript) is auto-rejected here with a mode-aware
        // diagnostic - the approval gate is never consulted for a tool that was hidden. Only tools the registry
        // actually serves are subject to being plan-hidden; bare/empty test registries are unaffected.
        final var served = servedToolNames.get();
        if (permissionService != null
                && permissionService.mode() == AgentMode.PLAN
                && tool.category() == ToolCategory.EDIT
                && served != null
                && served.contains(name)) {
            final var modeDenial = hiddenToolDiagnostic(name);
            unregisterRunningTool(ordinal);
            emit(new AgentEvent.ToolFinished(
                    name,
                    modeDenial,
                    false,
                    false,
                    ordinal,
                    currentSessionId.get(),
                    subagentId.get(),
                    subagentName.get(),
                    sessionWorkdir.get()));
            return new ToolOutcome(call.id(), name, modeDenial, false, false);
        }
        final Map<String, Object> args;
        try {
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
        } catch (final RuntimeException e) {
            final var diagnostic = "Invalid tool arguments JSON: " + argsJson;
            unregisterRunningTool(ordinal);
            emit(new AgentEvent.ToolFinished(
                    name,
                    diagnostic,
                    false,
                    false,
                    ordinal,
                    currentSessionId.get(),
                    subagentId.get(),
                    subagentName.get(),
                    sessionWorkdir.get()));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        emit(new AgentEvent.ToolStarted(
                name,
                argsJson,
                ordinal,
                currentSessionId.get(),
                subagentId.get(),
                subagentName.get(),
                sessionWorkdir.get()));
        final var gateDenial = gateTool(name, tool, args, call.id());
        if (gateDenial != null) {
            unregisterRunningTool(ordinal);
            emit(new AgentEvent.ToolFinished(
                    name,
                    gateDenial,
                    false,
                    false,
                    ordinal,
                    currentSessionId.get(),
                    subagentId.get(),
                    subagentName.get(),
                    sessionWorkdir.get()));
            return new ToolOutcome(call.id(), name, gateDenial, false, false);
        }
        final var toolStartNanos = precisionDurationMarker.now();
        final var context = toolContext(cancelled, callCancel::get, ordinal, settings);
        ToolResult result;
        try {
            result = toolExecutionService.execute(tool, args, context);
        } catch (final RuntimeException | Error exception) {
            // a crashing tool (or a throwing post-execution event observer, e.g. a ToolUsePost hook) must never
            // leave the transcript holding an open "running" tool card: normalize it into a failed result so the
            // unconditional tool-finished emission below still closes the card.
            LOGGER.log(
                    Level.WARNING,
                    "tool '" + name + "' crashed instead of returning a result"
                            + " - marking it finished with a diagnostic",
                    exception);
            result = ToolResult.error(toolCrashedDiagnostic(name, exception));
        } finally {
            unregisterRunningTool(ordinal);
            // the turn keeps running after a per-call cancel: only the tool result is marked
        }
        final var toolDurationMs = (precisionDurationMarker.now() - toolStartNanos) / 1_000_000;
        emit(new AgentEvent.ToolFinished(
                name,
                result.output(),
                result.success(),
                result.pending(),
                ordinal,
                currentSessionId.get(),
                subagentId.get(),
                subagentName.get(),
                sessionWorkdir.get()));
        return new ToolOutcome(
                call.id(),
                name,
                result.output(),
                result.success(),
                result.pending(),
                true,
                touchedPaths(name, args),
                toolDurationMs,
                // a pending result is not "new informative work" nor a failure: the model must not
                // count a still-growing subagent wait as an exploration round or a signal to retry
                !result.pending(),
                coverageKeys(name, args, context));
    }

    private ToolContext toolContext(
            final BooleanSupplier cancelled,
            final BooleanSupplier callCancelled,
            final long ordinal,
            final ChatSettings settings) {
        return new ToolContext(
                currentSessionId.get(),
                subagentId.get(),
                subagentName.get(),
                permissionService,
                userFeedbackService,
                todoStore,
                statusReporter,
                usageTracker,
                contextView,
                () -> sessionClosed.getAsBoolean() || cancelled.getAsBoolean(),
                settings,
                () -> sessionClosed.getAsBoolean() || callCancelled.getAsBoolean(),
                sessionWorkdir.get(),
                ordinal,
                delegationTools(),
                subagentBudget.get(),
                toolRegistry);
    }

    private Set<String> delegationTools() {
        if (toolRegistry == null) {
            return Set.of();
        }
        final var ceiling = effectiveAllowedTools.get();
        final var plan = permissionService != null && permissionService.mode() == AgentMode.PLAN;
        return toolRegistry
                .toWireTools(
                        sessionWorkdir.get(),
                        tool -> (ceiling == null || ceiling.contains(tool.name()))
                                && (!plan || tool.category() != ToolCategory.EDIT))
                .stream()
                .map(tool -> tool.function().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Runs the unified pre-tool gate for a tool call: the event is emitted synchronously (so the built-in
     * {@code ToolUsePermissionObserver} runs the session's permission rules and the
     * {@code ToolUseCallbackObserver} runs the configured {@code yca.hooks.ToolUsePreEvent} callbacks,
     * in that order). Returns the denial reason when the call must be blocked, or {@code null} to let it
     * run. This is the single authoritative denial point for every tool, bash included.
     */
    private String gateTool(
            final String name, final EmbeddedTool tool, final Map<String, Object> args, final String toolUseId) {
        final var result = new HookResult();
        final var workdir = args.get("workdir") instanceof final String w && !w.isBlank() ? w : null;
        final var root = sessionWorkdir.get();
        final var cwd = workdir == null
                ? root
                : ToolContext.empty(root).resolvePath(workdir).toAbsolutePath().normalize();
        final var context = new EventContextBuilder()
                .build(
                        permissionService,
                        chatSettings.get(),
                        currentSessionId.get(),
                        subagentId.get(),
                        subagentName.get(),
                        cwd);
        emitter.emit(new ToolUsePreEvent(
                context, name, args, toolUseId, result, permissionService, tool.category(), tool.readonly()));
        if (!result.forbidden()) {
            return null;
        }
        return "Permission denied by user" + (result.reason() == null ? "" : ": " + result.reason());
    }

    /** Extracts the file paths a file tool touched: the {@code path} arg, or {@code +++} headers for patches. */
    private static List<String> touchedPaths(final String name, final Map<String, Object> args) {
        if ("apply_patch".equals(name)) {
            if (!(args.get("patch") instanceof final String patch)) {
                return List.of();
            }
            final var out = new ArrayList<String>();
            for (final var line : patch.split("\n", -1)) {
                final var stripped = line.strip();
                if (!stripped.startsWith("+++ ")) {
                    continue;
                }
                var file = stripped.substring(4).strip();
                final var tab = file.indexOf('\t');
                if (tab >= 0) {
                    file = file.substring(0, tab).strip();
                }
                out.add(file);
            }
            return out;
        }
        final var path = args.get("path");
        return path == null ? List.of() : List.of(String.valueOf(path));
    }

    /**
     * Coverage keys of a successful read-only tool call, used by the exploration-plateau detector:
     * a normalized absolute path for file tools (delivered-output marker for read, pattern-aware for grep),
     * and for bash the configured bash-rules prefix + target from {@code PermissionService} - so the
     * recognized read-only families come from the config, never from a hardcoded command list.
     * Returns an empty list when the call does not map to a recognisable coverage target (writes,
     * todo updates, unrecognized commands): such calls are never plateau signals.
     */
    private List<String> coverageKeys(final String name, final Map<String, Object> args, final ToolContext context) {
        if ("read".equals(name)) {
            if (!(args.get("path") instanceof final String path) || path.isBlank()) {
                return List.of();
            }
            return List.of(ReadCoverage.key(context.resolvePath(path)));
        }
        if (FILE_READ_TOOLS.contains(name)) {
            final var path = args.get("path") instanceof final String p && !p.isBlank() ? p : "";
            if (path.isBlank()) {
                return List.of();
            }
            // page-aware key: re-reading the same 2k window is redundant, advancing through the file
            // (offset buckets) is novel - so legitimate paging never trips the plateau detector
            final var offset = args.get("offset") instanceof final Number n ? n.intValue() : 0;
            return List.of(context.resolvePath(path).normalize().toString() + "@" + (offset / 2048) + "k");
        }
        if ("bash".equals(name) && args.get("command") instanceof final String command && !command.isBlank()) {
            return permissionService == null ? List.of() : permissionService.bashCoverage(command);
        }
        if ("grep".equals(name)) {
            final var pattern = args.get("pattern") instanceof final String p ? p : "";
            final var path = args.get("path") instanceof final String p && !p.isBlank() ? p : "";
            // pattern-aware: a new search over the same root is novel, the same pattern over the
            // same root is redundant
            return path.isBlank()
                    ? (pattern.isBlank() ? List.of() : List.of("grep:" + pattern))
                    : List.of("grep:" + pattern + ":"
                            + context.resolvePath(path).normalize().toString());
        }
        if ("glob".equals(name) && args.get("path") instanceof final String path && !path.isBlank()) {
            return List.of(context.resolvePath(path).normalize().toString());
        }
        return List.of();
    }

    private static final Set<String> FILE_READ_TOOLS =
            Set.of("read", "edit_file", "write_file", "apply_patch", "ls", "find", "tree", "du");

    static String unknownToolDiagnostic(final String name) {
        return "Unknown tool \"" + name + "\": not in this session's toolset (built-in or enabled MCP)."
                + " Run /tools to list the valid tools; only exact names from the request's tools field are accepted.";
    }

    private static String toolCrashedDiagnostic(final String name, final Throwable exception) {
        final var cause =
                exception.getMessage() == null || exception.getMessage().isBlank()
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
        return "Tool \"" + name + "\" crashed before returning a result (" + sanitizeSingleLine(cause)
                + ") - the call was aborted and marked finished. Inspect the logs for the full stack trace.";
    }

    private static String sanitizeSingleLine(final String value) {
        return value.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
    }

    /**
     * Diagnostic surfaced back to the model when it calls a tool that exists but was hidden from the current
     * request's wire list by the mode (e.g. EDIT tools in plan mode). Read-only tools (READ/AGENT categories)
     * are never hidden, so the tool is state-changing; the message says so and re-anchors the model to the
     * mode's rules instead of letting the call reach the approval gate.
     */
    private String hiddenToolDiagnostic(final String name) {
        final var plan = permissionService != null && permissionService.mode() == AgentMode.PLAN;
        if (plan) {
            return "\"" + name + "\" is not available in plan mode - file-mutating tools (edit_file, write_file,"
                    + " apply_patch) are only offered in build mode. If this is part of the plan, describe it and"
                    + " record it with write_todo; execution happens when the user switches to build mode.";
        }
        final var mode = permissionService == null
                ? "this"
                : permissionService.mode().name().toLowerCase(Locale.ROOT);
        return "\"" + name + "\" was not offered in this request's tools field and cannot be called in " + mode
                + " mode. Describe what you intended instead and wait for an appropriate mode.";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private void emit(final Object event) {
        if (emitter != null) {
            emitter.emit(event);
        }
    }
}
