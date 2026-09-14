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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
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

@ApplicationScoped
public class SystemPromptBuilder {
    private static final List<String> SIBLING_CONTEXT_FILES = List.of("CLAUDE.md", "README.md");
    private static final List<String> PROJECT_SKILL_DIRS = List.of(".opencode/skills", ".claude/skills", ".yca/skills");

    /**
     * Hard ceiling on the assembled system prompt (rules + environment + project AGENTS.md + skills
     * + memory). It is message[0], preserved verbatim through compaction, so unbounded growth here
     * would alone push a request over the window in a deep repository. Tail-kept with a leading
     * marker so the durable instructions appended last (memory/agent) survive.
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
        final var plan = permissionService != null && permissionService.mode() == AgentMode.PLAN;
        if (plan) {
            sb.append("You are yca, a terminal coding agent, currently in PLAN mode.\n");
            sb.append(
                    "You must NOT modify the repository: no file creation, edits or deletions, and no state-changing shell commands. Only read-only tools are available.\n");
            sb.append(
                    "Your task is to analyze the codebase and produce a detailed, concrete implementation plan: the files to touch, the steps to take, the approach and trade-offs, and any risks. Do not apply any change, do not use write or edit tools, and finish with a clear plan the user can review.\n");
            sb.append(
                    "Use todo_write to record the plan: one pending item per concrete implementation step, in execution order. todo_write is the only state-changing tool allowed in this mode.\n");
            appendTodoGuidance(sb);
            sb.append('\n');
        } else {
            sb.append("You are yca, a terminal coding agent. You help the user with software engineering tasks.\n");
            sb.append(
                    "You can read, create and edit files, run shell commands and search the codebase through the provided tools.\n\n");
            sb.append("Rules:\n");
            sb.append("- Prefer the provided tools over printing code for the user to apply.\n");
            sb.append("- Read files before editing them; keep edits minimal and focused.\n");
            sb.append("- Use todo_write to track multi-step work and keep it up to date.\n");
            sb.append("- Use memory_write to persist durable facts, decisions or user preferences.\n");
            sb.append("- Verify your work (compile/test) when feasible. Be concise in final answers.\n");
            sb.append(
                    "- Paths: always work with absolute paths; resolve any relative path the user mentions against its intended root to form the full path before using a tool.\n");
            sb.append(
                    "- Target directory: if the user names an explicit directory for the task (for example \"generate/create/scaffold in <dir> ...\" or \"in /tmp/foo build ...\"), treat that directory as the target of the task and build there, regardless of where the tool was started. The working-directory repository is only where you launched; do not assume the task belongs in it, do not index it, and do not apply changes to it unless the user explicitly says so. If the task is a scaffold into an empty directory, create the project structure there from scratch and do not reuse the working-directory code.\n");
            appendTodoGuidance(sb);
            sb.append('\n');
        }
        sb.append("You may invoke a tool only if its exact name appears in the `tools` field of this request. "
                + "Do not invent, abbreviate, or guess tool names: any other name will be rejected. "
                + "If the task needs a capability none of the provided tools offers, tell the user "
                + "and propose the closest available tool instead of calling an unknown one.\n");
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
        sb.append("- Date: ").append(LocalDate.now()).append('\n');
        sb.append("- Current todo list:\n")
                .append(todoStore == null ? "(empty)" : todoStore.render())
                .append('\n');
        final var project = projectContext(projectDir);
        if (!project.isBlank()) {
            sb.append('\n').append(project).append('\n');
        }
        final var skills = skillsSection();
        if (!skills.isBlank()) {
            sb.append('\n').append(skills).append('\n');
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
        final var memory = memoryStore == null ? "" : memoryStore.promptSection(prompt);
        if (!memory.isBlank()) {
            sb.append('\n').append(memory).append('\n');
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
        return capSystemPrompt(sb.toString());
    }

    /**
     * Keeps the assembled system prompt within {@link #SYSTEM_PROMPT_CAP} characters, trimming the
     * front (the generic preamble) and keeping the tail (environment, project context, skills,
     * memory and agent) which carry the most load-bearing durable instructions.
     */
    private static String capSystemPrompt(final String prompt) {
        if (prompt.length() <= SYSTEM_PROMPT_CAP) {
            return prompt;
        }
        return "[...system prompt exceeded the " + SYSTEM_PROMPT_CAP
                + " char budget; " + (prompt.length() - SYSTEM_PROMPT_CAP) + " leading chars elided...]\n"
                + prompt.substring(prompt.length() - SYSTEM_PROMPT_CAP);
    }

    private void appendTodoGuidance(final StringBuilder sb) {
        if (todoPromptResolver == null) {
            return;
        }
        final var provider = providerManager == null ? null : providerManager.current();
        final var baseUrl = provider == null ? null : provider.baseUrl();
        final var mode = provider == null ? "auto" : provider.todoPrompt();
        final var guidance = todoPromptResolver.resolve(mode, baseUrl);
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

    private String skillsSection() {
        final var config = configuration.skills();
        if (config == null || !config.enabled()) {
            return "";
        }
        final var sb = new StringBuilder();
        final var seen = new java.util.HashSet<String>();
        var added = false;
        final var base = configuration.resolveWorkingDir();
        for (final var dir : skillDirectories(base)) {
            final var dirPath = Path.of(dir);
            if (!Files.isDirectory(dirPath)) {
                continue;
            }
            try (final var dirs = Files.list(dirPath)) {
                for (final var skillDir : dirs.sorted().toList()) {
                    if (!Files.isDirectory(skillDir)) {
                        continue;
                    }
                    final var skillFile = skillDir.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillFile)) {
                        continue;
                    }
                    if (!seen.add(skillDir.getFileName().toString())) {
                        continue;
                    }
                    try {
                        final var raw = Files.readString(skillFile);
                        final var parsed =
                                parseSkill(raw, skillDir.getFileName().toString());
                        sb.append("<skill name=\"")
                                .append(parsed.name())
                                .append("\">\n")
                                .append(parsed.description())
                                .append("\n</skill>\n");
                        added = true;
                    } catch (final IOException e) {
                        // skip unreadable
                    }
                }
            } catch (final IOException e) {
                // skip unreadable dir
            }
        }
        return added ? sb.toString() : "";
    }

    private List<String> skillDirectories(final Path base) {
        final var list = new ArrayList<String>();
        var dir = base;
        final var walk = new ArrayList<Path>();
        walk.add(dir);
        while (dir.getParent() != null) {
            dir = dir.getParent();
            walk.add(dir);
        }
        for (var i = walk.size() - 1; i >= 0; i--) {
            for (final var d : PROJECT_SKILL_DIRS) {
                list.add(walk.get(i).resolve(d).toString());
            }
        }
        list.add(Path.of(configuration.resolveStorageHome(), "skills").toString());
        final var home = configuration.resolveHome();
        list.add(Path.of(home, ".config", "opencode", "skills").toString());
        list.add(Path.of(home, ".claude", "skills").toString());
        list.add(Path.of(home, ".agents", "skills").toString());
        return list;
    }

    private static final String FRONTMATTER_START = "---\n";
    private static final String FRONTMATTER_END = "---";

    private static Skill parseSkill(final String raw, final String dirName) {
        var content = raw;
        var name = dirName;
        var description = "";
        if (content.startsWith(FRONTMATTER_START)) {
            final var end = content.indexOf('\n' + FRONTMATTER_END + "\n", FRONTMATTER_START.length());
            if (end > 0) {
                final var front = content.substring(FRONTMATTER_START.length(), end);
                for (final var line : front.lines().toList()) {
                    if (line.startsWith("name:") && line.length() > "name:".length()) {
                        final var candidate = line.substring("name:".length()).strip();
                        if (!candidate.isEmpty()) {
                            name = candidate;
                        }
                    } else if (line.startsWith("description:") && line.length() > "description:".length()) {
                        description = line.substring("description:".length()).strip();
                    }
                }
                content = content.substring(end + FRONTMATTER_END.length() + 2);
            }
        }
        if (description.isEmpty()) {
            description = firstMeaningfulLine(content);
        }
        return new Skill(name, description);
    }

    private static String firstMeaningfulLine(final String content) {
        for (final var line : content.lines().toList()) {
            final var stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return content.strip();
    }

    private record Skill(String name, String description) {}
}
