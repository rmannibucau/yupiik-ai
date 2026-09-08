package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptBuilderTest {

    @TempDir
    Path storageHome;

    @TempDir
    Path project;

    @Test
    void includesHierarchicalAndGlobalAgentsMd() throws Exception {
        Files.writeString(project.resolve("AGENTS.md"), "# Project AGENTS\nproject rule");
        Files.createDirectories(project.resolve("src").resolve("main"));
        final var nested = project.resolve("src").resolve("main");
        Files.writeString(nested.resolve("AGENTS.md"), "# Nested AGENTS\nnested rule");
        Files.writeString(storageHome.resolve("AGENTS.md"), "# Global AGENTS\nalways use tabs");
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", nested.toString(),
                "yca.skills.enabled", "true")), null, null, null, null);
        final var prompt = builder.build();
        assertTrue(prompt.contains("project rule"));
        assertTrue(prompt.contains("nested rule"));
        assertTrue(prompt.contains("always use tabs"));
    }

    @Test
    void includesGlobalSkill() throws Exception {
        final var skill = storageHome.resolve("skills").resolve("my-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: custom-name\ndescription: a skill\n---\nUse the skill body here.");
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true")), null, null, null, null);
        final var prompt = builder.build();
        assertTrue(prompt.contains("<skill name=\"custom-name\">"));
        assertTrue(prompt.contains("Use the skill body here."));
    }

    @Test
    void includesProjectSkillFromConventionalDir() throws Exception {
        final var skill = project.resolve(".opencode").resolve("skills").resolve("proj-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: proj-skill\n---\nproject skill body");
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true")), null, null, null, null);
        final var prompt = builder.build();
        assertTrue(prompt.contains("<skill name=\"proj-skill\">"));
        assertTrue(prompt.contains("project skill body"));
    }

    @Test
    void disabledSkillsAreOmitted() throws Exception {
        final var skill = storageHome.resolve("skills").resolve("my-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: my-skill\n---\nbody");
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "false")), null, null, null, null);
        final var prompt = builder.build();
        assertFalse(prompt.contains("<skill name=\"my-skill\">"));
    }

    @Test
    void planModeStatesReadOnlyAndPlanDelivery() throws Exception {
        final var cfg = config(Map.of("yca.mode", "plan"));
        final var permissions = new PermissionService(cfg);
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(cfg, null, null, permissions, new AgentProvider(cfg));
        final var prompt = builder.build();
        assertTrue(prompt.contains("PLAN mode"));
        assertTrue(prompt.contains("must NOT modify the repository"));
        assertFalse(prompt.contains("edit files"));
        assertFalse(prompt.contains("use todo_write"));
    }

    @Test
    void separateProjectDirScopesContextAndPersona() throws Exception {
        Files.writeString(project.resolve("README.md"), "# unrelated framework README\ndashboard internals");
        Files.writeString(project.resolve("AGENTS.md"), "# unrelated framework rule\nframework-specific rule");
        final var target = Files.createDirectory(project.getParent().resolve(project.getFileName() + "-scaffold"));
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.projectDir", target.toString(),
                "yca.skills.enabled", "true")), null, null, null, null);
        final var prompt = builder.build();
        assertTrue(prompt.contains("Target project directory: " + target.toAbsolutePath().normalize()),
                "persona should name the separate target project dir");
        assertFalse(prompt.contains("dashboard internals"),
                "unrelated workingDir README must not leak when projectDir points elsewhere");
        assertFalse(prompt.contains("framework-specific rule"),
                "unrelated workingDir AGENTS.md must not leak when projectDir points elsewhere");
    }

    @Test
    void promptDrivenTargetGuidanceIsEmittedForScaffoldRequests() throws Exception {
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true")), null, null, null, null);
        final var prompt = builder.build(
                "generate in /tmp/foo a frontend landing page for the yupiik fusion project");
        assertTrue(prompt.contains("Target directory: if the user names an explicit directory"),
                "the opencode-style target guidance rule should be present so the model follows the named target");
        assertFalse(prompt.contains("Target project directory:"),
                "no target persona is claimed from the prompt; the model infers the target from the instruction");
    }

    @Test
    void promptWithoutScaffoldTargetKeepsWorkingDirContext() throws Exception {
        Files.writeString(project.resolve("README.md"), "# my repo\nowner docs");
        final var builder = new SystemPromptBuilder(config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true")), null, null, null, null);
        final var prompt = builder.build("explain how the retry logic works");
        assertTrue(prompt.contains("owner docs"),
                "workingDir README stays when the prompt names no target dir");
        assertFalse(prompt.contains("Target project directory:"),
                "no target persona when the prompt names no target dir");
    }

    private static CodingAgentConfiguration config(final Map<String, String> overrides) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", "~/.yupiik/coding-agent");
        map.put("yca.workingDir", ".");
        map.put("yca.skills.enabled", "true");
        map.putAll(overrides);
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(map.get(key))).get();
    }
}