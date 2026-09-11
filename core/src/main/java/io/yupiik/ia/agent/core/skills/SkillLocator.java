package io.yupiik.ia.agent.core.skills;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves skills (SKILL.md files) from the project hierarchy (searched upwards from the
 * working directory) and from the global skill directories. Shared by the system prompt
 * builder and the skill tool.
 */
public final class SkillLocator {
    private static final List<String> PROJECT_SKILL_DIRS = List.of(".opencode/skills", ".claude/skills", ".yca/skills");

    private SkillLocator() {
    }

    public static List<Path> skillDirectories(final CodingAgentConfiguration configuration) {
        final var list = new ArrayList<Path>();
        final var base = configuration.resolveWorkingDir();
        final var walk = new ArrayList<Path>();
        var dir = base;
        walk.add(dir);
        while (dir.getParent() != null) {
            dir = dir.getParent();
            walk.add(dir);
        }
        for (var i = walk.size() - 1; i >= 0; i--) {
            for (final var d : PROJECT_SKILL_DIRS) {
                list.add(walk.get(i).resolve(d));
            }
        }
        list.add(Path.of(configuration.resolveStorageHome(), "skills"));
        list.add(Path.of(System.getProperty("user.home"), ".config", "opencode", "skills"));
        list.add(Path.of(System.getProperty("user.home"), ".claude", "skills"));
        return list;
    }

    public static List<String> skillNames(final CodingAgentConfiguration configuration) {
        final var out = new ArrayList<String>();
        for (final var dir : skillDirectories(configuration)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (final var dirs = Files.list(dir)) {
                for (final var skillDir : dirs.sorted().toList()) {
                    if (Files.isDirectory(skillDir) && Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                        final var name = skillDir.getFileName().toString();
                        if (!out.contains(name)) {
                            out.add(name);
                        }
                    }
                }
            } catch (final Exception e) {
                // skip unreadable dir
            }
        }
        return out;
    }

    public static Path find(final CodingAgentConfiguration configuration, final String name) {
        for (final var dir : skillDirectories(configuration)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (final var dirs = Files.list(dir)) {
                for (final var skillDir : dirs.toList()) {
                    if (Files.isDirectory(skillDir) && skillDir.getFileName().toString().equals(name)) {
                        final var file = skillDir.resolve("SKILL.md");
                        if (Files.isRegularFile(file)) {
                            return file;
                        }
                    }
                }
            } catch (final Exception e) {
                // skip unreadable dir
            }
        }
        return null;
    }
}