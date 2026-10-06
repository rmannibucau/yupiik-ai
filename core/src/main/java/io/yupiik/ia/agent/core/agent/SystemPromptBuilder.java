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

import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class SystemPromptBuilder {
    private static final List<String> SIBLING_CONTEXT_FILES = List.of("CLAUDE.md", "README.md");

    /**
     * Hard ceiling on the assembled system prompt (rules + environment + project AGENTS.md + skills
     * + agent prompt). It is message[0], preserved verbatim through compaction, so unbounded growth
     * here would alone push a request over the window in a deep repository. Tail-kept with a leading
     * marker so the durable instructions appended last (agent prompt, PLAN mode tail) survive.
     */
    private static final int SYSTEM_PROMPT_CAP = 32_000;

    private final CodingAgentConfiguration configuration;
    private final MemoryStore memoryStore;
    private final TodoStore todoStore;
    private final PermissionService permissionService;
    private final AgentProvider agentProvider;
    private final ProviderManager providerManager;
    private final TodoPromptResolver todoPromptResolver;
    private final MavenRepositoryKnowledgeService mavenRepositoryKnowledge;
    /** The session's per-tab selection (provider/model); when set the prompt caps follow it. */
    protected volatile ChatSettings settings;

    /** Attach the owning session's model/provider selection so this builder resolves the right caps. */
    public void attach(final ChatSettings sessionSettings) {
        this.settings = sessionSettings;
    }

    /** The session's effective provider: explicit per-tab override, else the process default. */
    private ProviderConfiguration provider(final ChatSettings sessionSettings) {
        if (providerManager == null) {
            return null;
        }
        if (sessionSettings != null && sessionSettings.providerId() != null) {
            final var byId = providerManager.provider(sessionSettings.providerId());
            if (byId != null) {
                return byId;
            }
        }
        return providerManager.current();
    }

    public SystemPromptBuilder(
            final CodingAgentConfiguration configuration,
            final MemoryStore memoryStore,
            final TodoStore todoStore,
            final PermissionService permissionService,
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final TodoPromptResolver todoPromptResolver,
            final MavenRepositoryKnowledgeService mavenRepositoryKnowledge) {
        this.configuration = configuration;
        this.memoryStore = memoryStore;
        this.todoStore = todoStore;
        this.permissionService = permissionService;
        this.agentProvider = agentProvider;
        this.providerManager = providerManager;
        this.todoPromptResolver = todoPromptResolver;
        this.mavenRepositoryKnowledge = mavenRepositoryKnowledge;
    }

    protected SystemPromptBuilder() {
        this.configuration = null;
        this.memoryStore = null;
        this.todoStore = null;
        this.permissionService = null;
        this.agentProvider = null;
        this.providerManager = null;
        this.todoPromptResolver = null;
        this.mavenRepositoryKnowledge = null;
    }

    public String build() {
        return build(null);
    }

    /**
     * Builds the system prompt.
     *
     * @param prompt the current (first) user message; may be null
     * @return the assembled system prompt
     */
    public String build(final String prompt) {
        return build(prompt, null);
    }

    /**
     * Builds the system prompt, optionally augmented with a custom agent's instructions on top of
     * the base prompt (rules, environment, project context, skills and memory).
     *
     * @param prompt the current (first) user message; may be null
     * @param agent the custom agent whose prompt is injected on top; may be null
     * @return the assembled system prompt
     */
    public String build(final String prompt, final AgentConfiguration agent) {
        final var sb = new StringBuilder();
        // stable core: byte-identical across turns and modes so provider-side prompt caches (implicit
        // or explicit breakpoints) keep hitting; the PLAN mode tail and the volatile session state
        // (see {@link #sessionState}) are appended at the very end of the request instead.
        sb.append("You are yca, a terminal coding agent. You help the user with software engineering tasks.\n");
        sb.append(
                "You can read, create and edit files, run shell commands and search the codebase through the provided tools.\n\n");
        sb.append("Rules:\n");
        sb.append("- Prefer the provided tools over printing code for the user to apply.\n");
        sb.append("- Read files before editing them; keep edits minimal and focused.\n");
        sb.append(
                "- Use write_todo to track multi-step work and keep it up to date; completed and cancelled items stay in the list until the user asks to clear it.\n");
        sb.append(
                "- The conversation carries <session-state> blocks with the current todo list, mode, date and memory; the most recent one is authoritative and a request may end without one when nothing changed. Read it at the start of every turn: pending items are the task queue - continue with them unless the user redirects. Keep the list accurate with write_todo as work progresses.\n");
        sb.append(
                "- The conversation may carry a <compacted-context> digest of earlier history: treat it as authoritative historical state; never reload, reconstruct or repeat actions already described in it - only call tools that serve the current task.\n");
        if (configuration.pruning().nudgeThresholdPercent() > 0
                && configuration.tools().manageContext() != null
                && configuration.tools().manageContext().enabled()) {
            sb.append(
                    "- The <session-state> tail reports the current context usage percentage and the prune/compaction thresholds; when it gets high, proactively call manage_context (action=prune or action=compact) instead of waiting for the hard limit.\n");
        }
        sb.append("- Use memory_write to persist durable facts, decisions or user preferences.\n");
        sb.append("- Verify your work (compile/test) when feasible. Be concise in final answers.\n");
        sb.append(
                "- Paths: always work with absolute paths; resolve any relative path the user mentions against its intended root to form the full path before using a tool.\n");
        sb.append(
                "- Target directory: if the user names an explicit directory for the task (for example \"generate/create/scaffold in <dir> ...\" or \"in /tmp/foo build ...\"), treat that directory as the target of the task and build there, regardless of where the tool was started. The working-directory repository is only where you launched; do not assume the task belongs in it, do not index it, and do not apply changes to it unless the user explicitly says so. If the task is a scaffold into an empty directory, create the project structure there from scratch and do not reuse the working-directory code.\n");
        appendTodoGuidance(sb);
        sb.append('\n');
        sb.append("You may invoke a tool only if its exact name appears in the `tools` field of this request. "
                + "Do not invent, abbreviate, or guess tool names: any other name will be rejected. "
                + "If the task needs a capability none of the provided tools offers, tell the user "
                + "and propose the closest available tool instead of calling an unknown one.\n");
        sb.append('\n');
        sb.append("Tool usage policy:\n");
        sb.append("- Use the dedicated search tools for code exploration: grep for content search, glob for file name "
                + "patterns, read_file to read files, list_dir to list directories. Do NOT use bash for these "
                + "(no `grep`/`rg`/`find`/`cat`/`ls`/`head`/`tail`/`sed` in bash): the dedicated tools are faster, "
                + "skip build output and ignored directories, and return self-describing results. Reserve bash for "
                + "actual system commands (git, mvn, npm, docker...).\n");
        sb.append("- Batch independent tool calls in a single reply: when several searches or reads do not depend on "
                + "each other, issue them all at once - consecutive read-only calls run concurrently, so N independent "
                + "calls cost one round-trip. Only sequence calls that need previous results as inputs.\n");
        sb.append(
                "- Avoid tiny repeated slices: when you need more context around a hit, read a larger window instead of many 30-line chunks.\n");
        sb.append('\n');
        final var workingDir = configuration.resolveWorkingDir();
        final var projectDir = configuration.resolveProjectDir();
        sb.append("Environment:\n");
        sb.append("- Working directory: ").append(workingDir).append('\n');
        if (!projectDir.equals(workingDir)) {
            sb.append("- Target project directory: ").append(projectDir).append('\n');
        }
        sb.append("- Platform: ")
                .append(System.getProperty("os.name"))
                .append(' ')
                .append(System.getProperty("os.version"))
                .append('\n');
        sb.append("- Java: ").append(System.getProperty("java.version")).append('\n');
        final var project = projectContext(projectDir);
        if (!project.isBlank()) {
            sb.append('\n').append(project).append('\n');
        }
        if (mavenRepositoryKnowledge != null) {
            final var sessionSkills = mavenRepositoryKnowledge.sessionSkillBlocks();
            if (!sessionSkills.isEmpty()) {
                sb.append('\n');
                for (final var block : sessionSkills) {
                    sb.append(block).append('\n');
                }
            }
        }
        if (agent != null && agentProvider != null) {
            final var agentPrompt = agentProvider.resolvePrompt(agent);
            if (agentPrompt != null && !agentPrompt.isBlank()) {
                sb.append("\n\n<agent name=\"")
                        .append(agent.name())
                        .append("\">\n")
                        .append(agentPrompt)
                        .append("\n</agent>\n");
            }
        }
        // mode tail last: only a mode switch rewrites this suffix, so every turn within a mode keeps
        // the whole system prompt byte-stable and provider caches keep hitting
        final var plan = permissionService != null && permissionService.mode() == AgentMode.PLAN;
        // durable memory lives in the stable prefix (message[0], built once per turn) so it is never
        // re-sent at every tool-call round; only a mid-turn memory_write refreshes it via the tail
        if (memoryStore != null) {
            final var memory = memoryStore.promptSection(prompt, resolvedMemoryBudget());
            if (!memory.isBlank()) {
                sb.append('\n').append(memory).append('\n');
            }
        }
        if (plan) {
            sb.append('\n');
            sb.append(
                    "Mode: PLAN. Repository mutations are forbidden by default: no file creation, edits or deletions, and no state-changing shell commands - write_todo is the only state-changing tool allowed.\n");
            sb.append(
                    "Todos are the plan, not a progress report: every item stays 'pending' - statuses only advance in build mode, once a step is actually executed.\n");
            sb.append(
                    "Analyze the codebase and deliver a detailed, concrete implementation plan (files to touch, steps, approach and trade-offs, risks), ready for the user to review before anything is applied.\n");
            sb.append(
                    "Record the plan with write_todo and keep the list in sync with the discussion: whenever a follow-up changes the plan (scope, steps, approach, trade-offs), update it in the same turn so it always mirrors the latest agreed plan. Completed or cancelled items from earlier build sessions are progress history: keep them.\n");
            final var hints = configuration.permissions() != null
                            && configuration.permissions().bash() != null
                    ? configuration.permissions().bash().readOnlyPromptHints()
                    : null;
            if (hints != null && !hints.isBlank()) {
                sb.append(hints).append('\n');
            }
        }
        return capSystemPrompt(sb.toString(), resolvedSystemPromptCap());
    }

    /**
     * Renders the volatile session state (mode, date, todo list): injected on the
     * wire as a trailing message of the request - never part of the system prompt -
     * so mutating state never invalidates the cached prefix. The mode line is the authoritative
     * per-request signal: it is re-rendered fresh regardless of how the mode was reached (slash
     * command, startup flag or session restore), so the model never has to infer it from stale
     * conversation artifacts. The durable memory is {@link #memorySection(String) part of the
     * stable system prefix} and is only re-appended here (as a caller-side refresh) when a
     * mid-turn {@code memory_write} changed it. Callers enclose the result in {@code <session-state>} tags.
     *
     * @param prompt the current user prompt used to select relevant memories; may be null
     * @return the rendered session state, without the enclosing tags
     */
    public String sessionState(final String prompt) {
        final var sb = new StringBuilder();
        sb.append("Date: ").append(LocalDate.now()).append('\n');
        if (permissionService != null) {
            sb.append("Mode: ").append(permissionService.mode().name()).append('\n');
            if (permissionService.mode() == AgentMode.PLAN) {
                sb.append(
                        "Plan mode: if this turn changes the plan, call write_todo to sync the todo list before answering.\n");
            }
        }
        sb.append("Current todo list:\n")
                .append(todoStore == null ? "(empty)" : todoStore.render())
                .append('\n');
        return sb.toString();
    }

    /**
     * The durable memory section, {@code "&lt;memory&gt;...&lt;/memory&gt;"} (capped and prompt-ranked).
     * Empty when memory is disabled or not stored.
     */
    public String memorySection(final String prompt) {
        return memoryStore == null ? "" : memoryStore.promptSection(prompt, resolvedMemoryBudget());
    }

    /**
     * Content fingerprint of the memory files, stable across reads and bumped on every write.
     * The agent loop compares it to the turn-start value to detect a mid-turn {@code memory_write}
     * and refresh the tail without re-reading the files at every round.
     */
    public int memoryVersion() {
        return memoryStore == null ? -1 : memoryStore.version();
    }

    /**
     * Memory injection budget: when {@code memory.maxRatio > 0} the configured
     * {@code memory.maxChars} is capped by {@code maxRatio} of the resolved system
     * prompt cap (so a small context window does not waste its budget on memory),
     * and a budget below 1000 chars disables memory entirely (auto-off: extraction
     * is skipped too, the store keeps the facts untouched).
     */
    public int resolvedMemoryBudget() {
        if (memoryStore == null) {
            return 0;
        }
        final var memory = configuration.memory();
        if (!memory.enabled()) {
            return 0;
        }
        final var maxChars = Math.max(0, memory.maxChars());
        if (memory.maxRatio() <= 0) {
            return maxChars;
        }
        final var ratioBudget = (int) Math.floor(resolvedSystemPromptCap() * memory.maxRatio());
        final var budget = Math.min(maxChars, ratioBudget);
        return budget < 1_000 ? 0 : budget;
    }

    /**
     * Resolves the effective system prompt max character budget for the current provider/model, following the
     * cascade: per-model {@code behavior.systemPromptMaxChars} > per-provider {@code tuning.systemPromptMaxChars} >
     * the global {@code llm.systemPromptMaxChars} > a value derived from the provider context window (big windows keep
     * {@link #SYSTEM_PROMPT_CAP}, small ones shrink to leave the usable input budget room for the messages and tools).
     */
    private int resolvedSystemPromptCap() {
        final var provider = providerManager == null ? null : provider(settings);
        final var model = provider == null
                ? null
                : (settings != null
                                && settings.model() != null
                                && !settings.model().isBlank()
                        ? settings.model()
                        : provider.model());
        final var perModel =
                provider == null || model == null ? 0 : providerManager.systemPromptMaxChars(provider, model);
        if (perModel > 0) {
            return perModel;
        }
        final var global = configuration == null || configuration.llm() == null
                ? 0
                : configuration.llm().systemPromptMaxChars();
        if (global > 0) {
            return global;
        }
        // effective window: per-model override first, then provider value (both explicit); 0 = unknown
        final var window = provider == null || model == null
                ? (provider == null ? 0 : provider.contextWindow())
                : providerManager.contextWindowOverride(provider, model);
        if (window <= 0) {
            return SYSTEM_PROMPT_CAP;
        }
        // mirror UsageTracker.usableWindow(): reserve a quarter of the window (capped at 20k) for output
        final var usable = window - Math.min(window / 4, 20_000);
        // budget roughly half of the usable input for the system prompt, ~4 chars per token, clamped to the hard cap
        return Math.max(2_000, Math.min(SYSTEM_PROMPT_CAP, (usable / 2) * 4));
    }

    /**
     * Keeps the assembled system prompt within {@code maxChars} characters, trimming the
     * front (the generic preamble) and keeping the tail (environment, project context, skills,
     * agent instructions and the PLAN mode tail) which carries the most load-bearing durable instructions.
     */
    private static String capSystemPrompt(final String prompt, final int maxChars) {
        if (prompt.length() <= maxChars) {
            return prompt;
        }
        return "[...system prompt exceeded the " + maxChars
                + " char budget; " + (prompt.length() - maxChars) + " leading chars elided...]\n"
                + prompt.substring(prompt.length() - maxChars);
    }

    /** The global {@code yca.todos.guidance}, or {@code auto} when unset. */
    private String globalGuidance() {
        if (configuration == null || configuration.todos() == null) {
            return "auto";
        }
        final var guidance = configuration.todos().guidance();
        return guidance == null || guidance.isBlank() ? "auto" : guidance;
    }

    private void appendTodoGuidance(final StringBuilder sb) {
        if (todoPromptResolver == null) {
            return;
        }
        final var provider = providerManager == null ? null : providerManager.current();
        final var baseUrl = provider == null ? null : provider.baseUrl();
        // a provider configuring its own guidance wins; the providers configuring none inherit the
        // global yca.todos.guidance (blank there means the usual 'auto' family detection)
        final var mode = provider != null && provider.todoPromptConfigured() ? provider.todoPrompt() : globalGuidance();
        final var agentMode = permissionService == null ? null : permissionService.mode();
        final var guidance = todoPromptResolver.resolve(mode, baseUrl, agentMode);
        if (!guidance.isBlank()) {
            sb.append("<todo-guidance>\n").append(guidance).append("\n</todo-guidance>\n");
        }
    }

    private String projectContext(final Path base) {
        final var sb = new StringBuilder();
        for (final var path : agentsFiles(base)) {
            if (path == null) {
                continue;
            }
            append(sb, "AGENTS.md", path);
        }
        for (final var name : SIBLING_CONTEXT_FILES) {
            final var path = base.resolve(name);
            if (Files.isRegularFile(path)) {
                append(sb, name, path);
            }
        }
        return sb.toString();
    }

    private void append(final StringBuilder sb, final String label, final Path path) {
        try {
            final var content = Files.readString(path);
            sb.append("<project-file name=\"")
                    .append(label)
                    .append("\" path=\"")
                    .append(path.toAbsolutePath().normalize())
                    .append("\">\n")
                    .append(content)
                    .append("\n</project-file>\n");
        } catch (final IOException e) {
            // skip unreadable
        }
    }

    private List<Path> agentsFiles(final Path base) {
        final var found = new ArrayList<Path>();
        var dir = base;
        while (dir != null) {
            final var candidate = dir.resolve("AGENTS.md");
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
                break;
            }
            dir = dir.getParent();
        }
        final var global = firstGlobalAgentsFile();
        if (global != null) {
            found.add(global);
        }
        return found;
    }

    private Path firstGlobalAgentsFile() {
        for (final var global : globalAgentsFiles()) {
            if (global != null) {
                return global;
            }
        }
        return null;
    }

    private List<Path> globalAgentsFiles() {
        final var home = configuration.resolveHome();
        final var list = new ArrayList<Path>();
        addIfRegular(list, Path.of(home, ".config", "opencode", "AGENTS.md"));
        addIfRegular(list, Path.of(configuration.resolveStorageHome()).resolve("AGENTS.md"));
        addIfRegular(list, Path.of(home, ".claude", "AGENTS.md"));
        return list;
    }

    private static void addIfRegular(final List<Path> list, final Path path) {
        if (Files.isRegularFile(path)) {
            list.add(path);
        }
    }
}
