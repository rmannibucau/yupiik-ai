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
package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Shared implementation of the interactive slash-command operations, so the
 * plain-terminal (CLI) and the terminal UI (TUI) do not duplicate the same
 * logic. This service only computes results and returns them as data records:
 * it never prints, never formats with ANSI or Markdown, and is synchronous.
 * Frontends are responsible for rendering the returned data (spinner,
 * transcript, colours) and for running it off their UI thread when needed.
 */
@ApplicationScoped
public class AgentSessionService {

    private final PermissionService permissionService;
    private final ChatClient chatClient;
    private final McpManager mcpManager;
    private final AgentProvider agentProvider;
    private final SessionStore sessionStore;
    private final ContextManager contextManager;
    private final SystemPromptBuilder systemPromptBuilder;
    private final MemoryStore memoryStore;
    private final StructuralCodeGraph structuralCodeGraph;
    private final PlanFileService planFileService;
    private final PlanTodoSeeder planTodoSeeder;
    private final TodoStore todoStore;
    private final CodingAgentConfiguration configuration;
    private final SnapshotStore snapshotStore;

    public AgentSessionService(
            final PermissionService permissionService,
            final ChatClient chatClient,
            final McpManager mcpManager,
            final AgentProvider agentProvider,
            final SessionStore sessionStore,
            final ContextManager contextManager,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryStore memoryStore,
            final StructuralCodeGraph structuralCodeGraph,
            final PlanFileService planFileService,
            final PlanTodoSeeder planTodoSeeder,
            final TodoStore todoStore,
            final CodingAgentConfiguration configuration,
            final SnapshotStore snapshotStore) {
        this.permissionService = permissionService;
        this.chatClient = chatClient;
        this.mcpManager = mcpManager;
        this.agentProvider = agentProvider;
        this.sessionStore = sessionStore;
        this.contextManager = contextManager;
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryStore = memoryStore;
        this.structuralCodeGraph = structuralCodeGraph;
        this.planFileService = planFileService;
        this.planTodoSeeder = planTodoSeeder;
        this.todoStore = todoStore;
        this.configuration = configuration;
        this.snapshotStore = snapshotStore;
    }

    public record ToolInfo(String name, String description) {}

    public record ServerTools(String server, List<ToolInfo> tools) {}

    public record ToolListing(boolean anyTool, List<ServerTools> servers, int matches) {}

    /**
     * Lists the MCP tools, grouped by server and filtered by the user-provided
     * terms (matched against the lowercase {@code server_tool} key). Only
     * servers with at least one matching tool are returned. {@code anyTool}
     * tells whether the MCP layer reported any server at all, letting the
     * frontend distinguish "no tools available" from "no tool matches".
     */
    public ToolListing tools(final String arg) {
        final var filters = new ArrayList<String>();
        for (final var token : arg.split(" ")) {
            final var filter = token.strip().toLowerCase(Locale.ROOT);
            if (!filter.isEmpty()) {
                filters.add(filter);
            }
        }
        final var byServer = new TreeMap<>(mcpManager.toolsByServer());
        final var servers = new ArrayList<ServerTools>();
        var count = 0;
        for (final var server : byServer.entrySet()) {
            final var tools = server.getValue().stream()
                    .sorted(Comparator.comparing(EmbeddedTool::name))
                    .filter(tool -> filters.stream().allMatch(filter -> {
                        final var rootKey = (server.getKey() + '_' + tool.name()).toLowerCase(Locale.ROOT);
                        return rootKey.contains(filter);
                    }))
                    .map(tool -> new ToolInfo(tool.name(), toolDescription(server.getKey(), tool)))
                    .toList();
            if (tools.isEmpty()) {
                continue;
            }
            servers.add(new ServerTools(server.getKey(), tools));
            count += tools.size();
        }
        return new ToolListing(!byServer.isEmpty(), servers, count);
    }

    private String toolDescription(final String server, final EmbeddedTool tool) {
        var description = tool.description() == null ? "" : tool.description().strip();
        final var prefix = '[' + server + "] ";
        if (description.startsWith(prefix)) {
            description = description.substring(prefix.length()).strip();
        }
        final var lineBreak = description.indexOf('\n');
        if (lineBreak >= 0) {
            description = description.substring(0, lineBreak).strip();
        }
        return description.isEmpty() || description.equals(tool.name()) ? null : description;
    }

    public record ModeChange(String message, String seedMessage, String seedError) {}

    /** Whether the typed input matches a resume alias ({@code yca.resumeAliases}). */
    public boolean isResumeAlias(final String input) {
        return configuration.isResumeAlias(input);
    }

    /**
     * Switches the agent working mode and, when entering {@code BUILD} mode,
     * attempts to seed the todo list from the last assistant plan.
     */
    public ModeChange setMode(final String arg, final String sessionId) {
        final var mode = AgentMode.fromConfigurationString(arg);
        permissionService.setMode(mode);
        String seedMessage = null;
        String seedError = null;
        if (mode == AgentMode.BUILD) {
            try {
                final var seeded = planTodoSeeder.seedFromLastAssistant(sessionId);
                seedMessage = seeded ? "todos seeded from the plan" : "no plan found to seed todos from";
            } catch (final RuntimeException e) {
                seedError = "todo seeding failed: " + e.getMessage();
            }
        }
        return new ModeChange("mode: " + mode.name().toLowerCase(Locale.ROOT), seedMessage, seedError);
    }

    /**
     * Sets the tool permission level. Returns {@code true} for "allow all",
     * {@code false} for "ask for approval", and {@code null} for an invalid
     * argument (the caller should show the usage hint).
     */
    public Boolean setPermissions(final String arg) {
        final var v = arg == null ? "" : arg.strip();
        if (v.equals("all") || v.equals("unsafe")) {
            permissionService.setPermitAll(true);
            return true;
        }
        if (v.equals("none") || v.equals("safe")) {
            permissionService.setPermitAll(false);
            return false;
        }
        return null;
    }

    public record PermissionsInfo(
            boolean permitAll, AgentMode agentMode, PermissionMode permissionMode, List<String> sessionGrants) {}

    /**
     * Returns the current permission state for the {@code /permissions show} command.
     * Session grants are explicitly session-scoped (in-memory, lost on restart).
     */
    public PermissionsInfo permissionsInfo() {
        final var configured = configuration.permissions();
        final var mode = configured == null ? null : configured.mode();
        return new PermissionsInfo(
                permissionService.isPermitAll(), permissionService.mode(), mode, permissionService.sessionGrants());
    }

    /**
     * Sets the chat model, stripping an optional leading {@code $} prefix.
     * Returns the effective model name.
     */
    public String setModel(final String arg) {
        final var model = arg.strip();
        final var effective = model.startsWith("$") ? model.substring(1) : model;
        chatClient.setModel(effective);
        return effective;
    }

    /**
     * Sets the active provider; returns the provider id.
     */
    public String setProvider(final String arg) {
        final var id = arg.strip();
        chatClient.setProvider(id);
        return id;
    }

    /**
     * Sets the active custom agent if it exists; returns {@code null} when the
     * agent is unknown (the caller should report it).
     */
    public String setAgent(final String arg) {
        final var name = arg.strip();
        return agentProvider.byName(name) == null ? null : name;
    }

    public record ContextMessage(String role, String firstLine, String content, String toolName) {}

    public record ContextDump(String systemPrompt, String sessionId, List<ContextMessage> messages) {}

    /**
     * Builds the raw context dump: the generated system prompt and the full
     * session messages. The frontend decides how to format and how much of
     * each message to show.
     */
    public ContextDump context(final String sessionId) {
        final var messages = sessionStore.load(sessionId);
        final var toolNamesByCallId = Message.toolNames(messages);
        final var lines = new ArrayList<ContextMessage>(messages.size());
        for (final var message : messages) {
            final var content = String.valueOf(message.content());
            lines.add(new ContextMessage(
                    message.role(), firstLine(content), content, Message.toolName(message, toolNamesByCallId)));
        }
        return new ContextDump(systemPromptBuilder.build(), sessionId, lines);
    }

    public record CompactionApplied(boolean applied, String message, String error) {}

    /**
     * Compacts the context window of a persisted session. Returns nothing to
     * compact when empty, and reports a fallback/error when the summarizer
     * could not run (so the frontend never shows a false success).
     */
    public CompactionApplied compact(final String sessionId) {
        return compact(sessionId, false);
    }

    /**
     * Compacts the context window of a persisted session. {@code force} bypasses the
     * within-budget guard so compaction runs even below the thresholds. Returns nothing
     * to compact when empty, and reports a fallback/error when the summarizer could not
     * run (so the frontend never shows a false success).
     */
    public CompactionApplied compact(final String sessionId, final boolean force) {
        final var history = sessionStore.load(sessionId);
        if (history.isEmpty()) {
            return new CompactionApplied(false, "nothing to compact", null);
        }
        final var result = contextManager.compact(history, force);
        if (!result.changed()) {
            return new CompactionApplied(false, "context already within budget", null);
        }
        sessionStore.replace(sessionId, result.messages());
        return switch (result.mode()) {
            case SUMMARY -> new CompactionApplied(true, "context compacted (summarized)", null);
            case CLEARED -> new CompactionApplied(true, "old tool outputs cleared", null);
            case SHRUNK -> new CompactionApplied(true, "verbose tool outputs shrunk", null);
            case TRUNCATE ->
                new CompactionApplied(true, "context truncated locally (summarizer failed)", result.error());
            case NONE -> new CompactionApplied(false, "context already within budget", null);
        };
    }

    public record SessionInfo(String id, Path path) {}

    /**
     * Returns the current session identifier and its on-disk location.
     */
    public SessionInfo sessionInfo(final String sessionId) {
        return new SessionInfo(sessionId, sessionStore.path(sessionId));
    }

    /**
     * Rewrites the current session to disk from its persisted transcript. Every
     * append is already synchronous, so this is mostly a defensive flush that
     * guarantees a clean, fully-written session file.
     */
    public String flush(final String sessionId) {
        final var history = sessionStore.load(sessionId);
        if (history.isEmpty()) {
            return "nothing to flush";
        }
        sessionStore.replace(sessionId, history);
        return "session flushed";
    }

    public record HousekeepingReport(int compressed, int deleted, int kept) {}

    /**
     * Runs the session housekeeping (retention cleanup, compression, empty
     * session removal) and returns the resulting report.
     */
    public HousekeepingReport housekeeping() {
        final var report = sessionStore.housekeeping(true);
        return new HousekeepingReport(report.compressed(), report.deleted(), report.kept());
    }

    /**
     * Resyncs the structural code graph index. Throws when the resync fails so
     * the caller can surface the error.
     */
    public String resyncGraph() {
        return structuralCodeGraph.resync();
    }

    public record PlanExport(Path path, String original, String error) {}

    /**
     * Exports the current plan (plan mode only) to a file, returning the path
     * and the exact content written. The {@code error} member is set when the
     * export cannot happen.
     */
    public PlanExport exportPlan(final String sessionId, final String arg, final String titleFallback) {
        if (permissionService.mode() != AgentMode.PLAN) {
            return new PlanExport(null, null, "editplan is only available in plan mode (run /mode plan)");
        }
        final var plan = planTodoSeeder.plan(sessionId);
        if (plan == null || plan.isBlank()) {
            return new PlanExport(null, null, "no plan to edit yet - produce a plan first");
        }
        final var path = (arg == null || arg.isBlank())
                ? planFileService.defaultPath(
                        Path.of(configuration.resolveStorageHome()),
                        titleFallback == null ? "" : titleFallback,
                        sessionId)
                : Path.of(arg).toAbsolutePath();
        final var content = planFileService.render(plan, todoStore.all());
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            Files.writeString(path, content);
            return new PlanExport(path, content, null);
        } catch (final IOException e) {
            return new PlanExport(path, null, "editplan failed to write " + path + ": " + e.getMessage());
        }
    }

    public record PlanReload(boolean changed, String message, String error) {}

    /**
     * Reloads an edited plan file: when its content changed, the parsed plan
     * and todos are applied to the session. Returns the resulting status
     * message, or {@code changed=false} when the file was not modified.
     */
    public PlanReload reloadPlan(final String sessionId, final Path path, final String original) {
        if (!Files.isRegularFile(path)) {
            return new PlanReload(false, null, "plan file not found: " + path);
        }
        final String edited;
        try {
            edited = Files.readString(path);
        } catch (final IOException e) {
            return new PlanReload(false, null, "editplan failed to read " + path + ": " + e.getMessage());
        }
        if (edited.equals(original)) {
            return new PlanReload(false, "no changes in the plan (nothing to reload)", null);
        }
        final var parsed = planFileService.parse(edited);
        final var applied = planFileService.reload(sessionId, parsed);
        return new PlanReload(true, applied, null);
    }

    public record MemoryContent(String global, String project) {}

    /**
     * Reads global and project memory. Empty strings mean "no memory".
     */
    public MemoryContent memory() {
        return new MemoryContent(memoryStore.readGlobal(), memoryStore.readProject());
    }

    public record AgentOverride(String agentName, String prompt) {}

    /**
     * Parses an optional leading {@code @agent} prompt override. When the name
     * does not match a configured agent the whole original text is kept as the
     * prompt and no agent is selected.
     */
    public AgentOverride agentOverride(final String text) {
        final var stripped = text == null ? "" : text.strip();
        if (stripped.startsWith("@")) {
            final var space = stripped.indexOf(' ');
            if (space > 1) {
                final var name = stripped.substring(1, space).strip();
                final var prompt = stripped.substring(space + 1).strip();
                return agentProvider.byName(name) != null
                        ? new AgentOverride(name, prompt)
                        : new AgentOverride(null, text);
            }
        }
        return new AgentOverride(null, text);
    }

    public record SnapshotStatus(String checkpoint, List<String> summaries) {}

    /**
     * {@code save [label]} sets an explicit checkpoint; {@code cancel} clears it; {@code status}
     * reports the active checkpoint and retained summaries. Completion returns a short status message.
     */
    public String snapshot(final String arg, final String sessionId) {
        final var trimmed = arg == null ? "" : arg.strip();
        final var lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("save")) {
            final var label = trimmed.substring("save".length()).strip();
            final var cp = snapshotStore.save(sessionId, label.isBlank() ? null : label);
            return "checkpoint saved: " + cp.label();
        }
        if (lower.equals("cancel")) {
            snapshotStore.cancel(sessionId);
            return "checkpoint cancelled";
        }
        if (lower.startsWith("restore")) {
            final var summary = trimmed.substring("restore".length()).strip();
            final var retained = snapshotStore.restore(sessionId, summary.isBlank() ? "manual snapshot" : summary);
            return retained.summary().isBlank()
                    ? "snapshot state cleared (no summary provided)"
                    : "snapshot recorded (summary injected in the system prompt each turn)";
        }
        // status (default)
        final var checkpoint = snapshotStore.checkpointLabel(sessionId);
        final var summaries =
                snapshotStore.retained(sessionId).stream().map(r -> r.summary()).toList();
        final var cp = checkpoint == null ? "(none)" : checkpoint;
        return "checkpoint: " + cp
                + (summaries.isEmpty()
                        ? " - no retained summaries"
                        : " - " + summaries.size() + " retained summary(ies)");
    }

    /**
     * The retained snapshot summaries to inject into the system prompt for a session, most recent last.
     */
    public List<SnapshotStore.Retained> snapshotSummaries(final String sessionId) {
        return snapshotStore.retained(sessionId);
    }

    /**
     * First, non-empty line of a text, collapsed to a single line.
     */
    public static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).strip();
    }
}
