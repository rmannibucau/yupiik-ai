package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.todo.TodoStore;

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
    private static final int FILE_CAP = 4000;

    private final CodingAgentConfiguration configuration;
    private final MemoryStore memoryStore;
    private final TodoStore todoStore;
    private final PermissionService permissionService;
    private final AgentProvider agentProvider;

    public SystemPromptBuilder(final CodingAgentConfiguration configuration, final MemoryStore memoryStore,
                               final TodoStore todoStore, final PermissionService permissionService,
                               final AgentProvider agentProvider) {
        this.configuration = configuration;
        this.memoryStore = memoryStore;
        this.todoStore = todoStore;
        this.permissionService = permissionService;
        this.agentProvider = agentProvider;
    }

    protected SystemPromptBuilder() {
        this.configuration = null;
        this.memoryStore = null;
        this.todoStore = null;
        this.permissionService = null;
        this.agentProvider = null;
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
            sb.append("You must NOT modify the repository: no file creation, edits or deletions, and no state-changing shell commands. Only read-only tools are available.\n");
            sb.append("Your task is to analyze the codebase and produce a detailed, concrete implementation plan: the files to touch, the steps to take, the approach and trade-offs, and any risks. Do not apply any change, do not use write or edit tools, and finish with a clear plan the user can review.\n");
            sb.append("Use todo_write to record the plan: one pending item per concrete implementation step, in execution order. todo_write is the only state-changing tool allowed in this mode.\n\n");
        } else {
            sb.append("You are yca, a terminal coding agent. You help the user with software engineering tasks.\n");
            sb.append("You can read, create and edit files, run shell commands and search the codebase through the provided tools.\n\n");
            sb.append("Rules:\n");
            sb.append("- Prefer the provided tools over printing code for the user to apply.\n");
            sb.append("- Read files before editing them; keep edits minimal and focused.\n");
            sb.append("- Use todo_write to track multi-step work and keep it up to date.\n");
            sb.append("- Use memory_write to persist durable facts, decisions or user preferences.\n");
            sb.append("- Verify your work (compile/test) when feasible. Be concise in final answers.\n");
            sb.append("- Paths: always work with absolute paths; resolve any relative path the user mentions against its intended root to form the full path before using a tool.\n");
            sb.append("- Target directory: if the user names an explicit directory for the task (for example \"generate/create/scaffold in <dir> ...\" or \"in /tmp/foo build ...\"), treat that directory as the target of the task and build there, regardless of where the tool was started. The working-directory repository is only where you launched; do not assume the task belongs in it, do not index it, and do not apply changes to it unless the user explicitly says so. If the task is a scaffold into an empty directory, create the project structure there from scratch and do not reuse the working-directory code.\n\n");
        }
        final var workingDir = configuration.resolveWorkingDir();
        final var projectDir = configuration.resolveProjectDir();
        sb.append("Environment:\n");
        sb.append("- Working directory: ").append(workingDir).append('\n');
        if (!projectDir.equals(workingDir)) {
            sb.append("- Target project directory: ").append(projectDir).append('\n');
        }
        sb.append("- Platform: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        sb.append("- Java: ").append(System.getProperty("java.version")).append('\n');
        sb.append("- Date: ").append(LocalDate.now()).append('\n');
        sb.append("- Current todo list:\n").append(todoStore == null ? "(empty)" : todoStore.render()).append('\n');
        final var project = projectContext(projectDir);
        if (!project.isBlank()) {
            sb.append('\n').append(project).append('\n');
        }
        final var skills = skillsSection();
        if (!skills.isBlank()) {
            sb.append('\n').append(skills).append('\n');
        }
        final var memory = memoryStore == null ? "" : memoryStore.promptSection();
        if (!memory.isBlank()) {
            sb.append('\n').append(memory).append('\n');
        }
        if (agent != null && agentProvider != null) {
            final var agentPrompt = agentProvider.resolvePrompt(agent);
            if (agentPrompt != null && !agentPrompt.isBlank()) {
                sb.append("\n\n<agent name=\"").append(agent.name()).append("\">\n")
                        .append(agentPrompt)
                        .append("\n</agent>\n");
            }
        }
        return sb.toString();
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
            var content = Files.readString(path);
            if (content.length() > FILE_CAP) {
                content = content.substring(0, FILE_CAP) + "\n[...truncated...]";
            }
            sb.append("<project-file name=\"").append(label).append("\" path=\"")
                    .append(path.toAbsolutePath().normalize()).append("\">\n").append(content).append("\n</project-file>\n");
        } catch (final IOException e) {
            // skip unreadable
        }
    }

    private List<Path> agentsFiles(final Path base) {
        final var found = new ArrayList<Path>();
        final var walk = new ArrayList<Path>();
        var dir = base;
        walk.add(dir);
        while (dir.getParent() != null) {
            dir = dir.getParent();
            walk.add(dir);
        }
        for (var i = walk.size() - 1; i >= 0; i--) {
            final var candidate = walk.get(i).resolve("AGENTS.md");
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
            }
        }
        for (final var global : globalAgentsFiles()) {
            found.add(global);
        }
        return found;
    }

    private List<Path> globalAgentsFiles() {
        final var list = new ArrayList<Path>();
        addIfRegular(list, Path.of(configuration.resolveStorageHome()).resolve("AGENTS.md"));
        addIfRegular(list, Path.of(System.getProperty("user.home"), ".config", "opencode", "AGENTS.md"));
        addIfRegular(list, Path.of(System.getProperty("user.home"), ".claude", "AGENTS.md"));
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
                        final var parsed = parseSkill(raw, skillDir.getFileName().toString());
                        sb.append("<skill name=\"").append(parsed.name()).append("\">\n")
                                .append(parsed.content()).append("\n</skill>\n");
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
        list.add(Path.of(System.getProperty("user.home"), ".config", "opencode", "skills").toString());
        list.add(Path.of(System.getProperty("user.home"), ".claude", "skills").toString());
        return list;
    }

    private static final String FRONTMATTER_START = "---\n";
    private static final String FRONTMATTER_END = "---";

    private static Skill parseSkill(final String raw, final String dirName) {
        var content = raw;
        var name = dirName;
        if (content.startsWith(FRONTMATTER_START)) {
            final var end = content.indexOf('\n' + FRONTMATTER_END + "\n", FRONTMATTER_START.length());
            if (end > 0) {
                final var front = content.substring(FRONTMATTER_START.length(), end);
                final var nameLine = front.lines().filter(l -> l.startsWith("name:")).findFirst().orElse("");
                if (!nameLine.isBlank()) {
                    name = nameLine.substring("name:".length()).strip();
                }
                content = content.substring(end + FRONTMATTER_END.length() + 2);
            }
        }
        if (content.length() > FILE_CAP) {
            content = content.substring(0, FILE_CAP) + "\n[...truncated...]";
        }
        return new Skill(name, content.strip());
    }

    private record Skill(String name, String content) {
    }
}