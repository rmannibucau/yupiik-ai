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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.skills.SkillLocator;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemPromptBuilderTest {

    @TempDir
    Path storageHome;

    @TempDir
    Path project;

    @Test
    void includesNearestAndFirstGlobalAgentsMd() throws Exception {
        Files.writeString(project.resolve("AGENTS.md"), "# Project AGENTS\nproject rule");
        Files.createDirectories(project.resolve("src").resolve("main"));
        final var nested = project.resolve("src").resolve("main");
        Files.writeString(nested.resolve("AGENTS.md"), "# Nested AGENTS\nnested rule");
        final var fakeHome = storageHome.resolve("home");
        final var configAgents = fakeHome.resolve(".config").resolve("opencode").resolve("AGENTS.md");
        Files.createDirectories(configAgents.getParent());
        Files.writeString(configAgents, "# Config AGENTS\nalways use tabs");
        final var storageAgents =
                fakeHome.resolve(".yupiik").resolve("coding-agent").resolve("AGENTS.md");
        Files.createDirectories(storageAgents.getParent());
        Files.writeString(storageAgents, "# Storage AGENTS\nstorage rule");
        final var builder = new SystemPromptBuilder(
                config(Map.of(
                        "yca.home", fakeHome.toString(),
                        "yca.storageHome", storageAgents.getParent().toString(),
                        "yca.workingDir", nested.toString(),
                        "yca.skills.enabled", "true")),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        final var prompt = builder.build();
        assertTrue(prompt.contains("nested rule"));
        assertFalse(
                prompt.contains("project rule"),
                "only the nearest project AGENTS.md should be loaded, not the full ancestor stack");
        assertTrue(
                prompt.contains("always use tabs"),
                "opencode-precedence global (~/.config/opencode/AGENTS.md) should win over storage-home");
        assertFalse(prompt.contains("storage rule"), "only the first existing global AGENTS.md should be loaded");
    }

    @Test
    void skillsLocatorIncludesAgentsGlobalDir() {
        final var dirs = SkillLocator.skillDirectories(config(Map.of()));
        assertTrue(
                dirs.contains(Path.of(System.getProperty("user.home"), ".agents", "skills")),
                "~/.agents/skills should be a global skill directory: " + dirs);
    }

    @Test
    void skillsAreNotInjectedInThePromptButExposedViaTheSkillToolCatalog() throws Exception {
        final var skill = storageHome.resolve("skills").resolve("my-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: custom-name\ndescription: a skill\n---\nUse the skill body here.");
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true"));
        final var builder = new SystemPromptBuilder(cfg, null, null, null, null, null, null, null);
        final var prompt = builder.build();
        assertFalse(
                prompt.contains("<skill name=\"custom-name\">"),
                "no skill block should be inlined in the system prompt (mirroring opencode)");
        assertFalse(
                prompt.contains("Use the skill body here."),
                "skill bodies should never be inlined; they load on demand via the skill tool");
        final var catalog = SkillLocator.availableSkillsCatalog(cfg);
        assertTrue(catalog.contains("<skill><name>custom-name</name>"), "catalog should list the skill: " + catalog);
        assertTrue(
                catalog.contains("<description>a skill</description>"),
                "catalog should carry the description: " + catalog);
    }

    @Test
    void skillsAreNotInjectedInThePromptForProjectSkillsEither() throws Exception {
        final var skill = project.resolve(".opencode")
                .resolve("skills")
                .resolve("proj-skill")
                .resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: proj-skill\n---\nproject skill body");
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true"));
        final var builder = new SystemPromptBuilder(cfg, null, null, null, null, null, null, null);
        final var prompt = builder.build();
        assertFalse(
                prompt.contains("<skill name=\"proj-skill\">"),
                "no skill block should be inlined in the system prompt");
        assertTrue(
                SkillLocator.availableSkillsCatalog(cfg).contains("<skill><name>proj-skill</name>"),
                "the skill tool catalog should still list project skills");
    }

    @Test
    void disabledSkillsAreOmitted() throws Exception {
        final var skill = storageHome.resolve("skills").resolve("my-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: my-skill\n---\nbody");
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "false"));
        final var builder = new SystemPromptBuilder(cfg, null, null, null, null, null, null, null);
        final var prompt = builder.build();
        assertFalse(prompt.contains("<skill name=\"my-skill\">"));
    }

    @Test
    void volatileSessionStateIsOutsideTheSystemPrompt() throws Exception {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString()));
        final var todos = new io.yupiik.ia.agent.core.todo.TodoStore(
                cfg,
                new JsonMapperImpl(
                        List.of(new io.yupiik.ia.agent.core.todo.TodoItem$FusionJsonCodec()), key -> Optional.empty()));
        todos.replace(List.of(new io.yupiik.ia.agent.core.todo.TodoItem("step one", "pending")));
        final var builder = new SystemPromptBuilder(cfg, null, todos, null, null, null, null, null);
        final var prompt = builder.build();
        assertFalse(prompt.contains("Current todo list"), "todos must not be part of the system prompt anymore");
        assertFalse(prompt.contains("Date: "), "the date must not be part of the system prompt anymore");
        final var state = builder.sessionState(null);
        assertTrue(state.contains("Date: "), "the session state must expose the date");
        assertFalse(state.contains("Mode:"), "without a permission service there is no mode to report");
        assertTrue(
                state.contains("Current todo list:") && state.contains("step one"),
                "the session state must expose the current todos: " + state);
    }

    @Test
    void sessionStateReportsTheCurrentMode() throws Exception {
        final var cfg = config(Map.of("yca.mode", "plan"));
        final var permissions = new PermissionService(cfg);
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(cfg, null, null, permissions, null, null, null, null);
        assertTrue(
                builder.sessionState(null).contains("Mode: PLAN"), "plan mode must be reported in the volatile tail");

        permissions.setMode(AgentMode.BUILD);
        final var state = builder.sessionState(null);
        assertTrue(
                state.contains("Mode: BUILD"),
                "build mode must be reported in the volatile tail, switching from plan: " + state);
    }

    @Test
    void planModeStatesReadOnlyAndPlanDelivery() throws Exception {
        final var cfg = config(Map.of("yca.mode", "plan"));
        final var permissions = new PermissionService(cfg);
        permissions.setMode(AgentMode.PLAN);
        final var builder =
                new SystemPromptBuilder(cfg, null, null, permissions, new AgentProvider(cfg), null, null, null);
        final var prompt = builder.build();
        assertTrue(prompt.contains("Mode: PLAN"));
        assertTrue(prompt.contains("Repository mutations are forbidden by default"));
        assertTrue(prompt.contains("write_todo is the only state-changing tool allowed"));
        assertTrue(prompt.contains("every item stays 'pending'"));
        assertTrue(prompt.contains("statuses only advance in build mode"));
        assertTrue(prompt.contains("ready for the user to review"));
        // the mode constraint is a tail: the mode-neutral identity (which mentions editing) comes first
        assertTrue(prompt.indexOf("edit files") < prompt.indexOf("Mode: PLAN"));
        // the system prompt is deterministic now: no volatile session state inside
        assertEquals(prompt, builder.build());
    }

    @Test
    void separateProjectDirScopesContextAndPersona() throws Exception {
        Files.writeString(project.resolve("README.md"), "# unrelated framework README\ndashboard internals");
        Files.writeString(project.resolve("AGENTS.md"), "# unrelated framework rule\nframework-specific rule");
        final var target = Files.createDirectory(project.getParent().resolve(project.getFileName() + "-scaffold"));
        final var builder = new SystemPromptBuilder(
                config(Map.of(
                        "yca.storageHome", storageHome.toString(),
                        "yca.workingDir", project.toString(),
                        "yca.projectDir", target.toString(),
                        "yca.skills.enabled", "true")),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        final var prompt = builder.build();
        assertTrue(
                prompt.contains(
                        "Target project directory: " + target.toAbsolutePath().normalize()),
                "persona should name the separate target project dir");
        assertFalse(
                prompt.contains("dashboard internals"),
                "unrelated workingDir README must not leak when projectDir points elsewhere");
        assertFalse(
                prompt.contains("framework-specific rule"),
                "unrelated workingDir AGENTS.md must not leak when projectDir points elsewhere");
    }

    @Test
    void promptDrivenTargetGuidanceIsEmittedForScaffoldRequests() throws Exception {
        final var builder = new SystemPromptBuilder(
                config(Map.of(
                        "yca.storageHome", storageHome.toString(),
                        "yca.workingDir", project.toString(),
                        "yca.skills.enabled", "true")),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        final var prompt =
                builder.build("generate in ~/projects/foo a frontend landing page for the yupiik fusion project");
        assertTrue(
                prompt.contains("Target directory: if the user names an explicit directory"),
                "the opencode-style target guidance rule should be present so the model follows the named target");
        assertFalse(
                prompt.contains("Target project directory:"),
                "no target persona is claimed from the prompt; the model infers the target from the instruction");
    }

    @Test
    void promptWithoutScaffoldTargetKeepsWorkingDirContext() throws Exception {
        Files.writeString(project.resolve("README.md"), "# my repo\nowner docs");
        final var builder = new SystemPromptBuilder(
                config(Map.of(
                        "yca.storageHome", storageHome.toString(),
                        "yca.workingDir", project.toString(),
                        "yca.skills.enabled", "true")),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        final var prompt = builder.build("explain how the retry logic works");
        assertTrue(prompt.contains("owner docs"), "workingDir README stays when the prompt names no target dir");
        assertFalse(
                prompt.contains("Target project directory:"), "no target persona when the prompt names no target dir");
    }

    @Test
    void todoGuidanceAutoOpenAiDefaultForUnknownFamily() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl", "https://mystery.example/v1",
                "yca.provider.providers.0.model", "m"));
        final var builder = builder(cfg, todoResolver(), providerManager(cfg));
        final var prompt = builder.build();
        assertTrue(prompt.contains("<todo-guidance>"), "guidance appendix should be present by default");
        assertTrue(prompt.contains("Do not batch up several todos"), "default family should map to the OpenAI block");
    }

    @Test
    void todoGuidanceAnthropicFamilyUsesAnthropicBlock() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl", "https://api.anthropic.com",
                "yca.provider.providers.0.model", "m"));
        final var builder = builder(cfg, todoResolver(), providerManager(cfg));
        final var prompt = builder.build();
        assertTrue(prompt.contains("VERY frequently"), "anthropic family should map to the Anthropic block");
    }

    @Test
    void todoGuidanceVerbatimOverride() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions",
                "yca.provider.providers.0.model", "m",
                "yca.provider.providers.0.prompt.todo", "custom guidance text"));
        final var builder = builder(cfg, todoResolver(), providerManager(cfg));
        final var prompt = builder.build();
        assertTrue(prompt.contains("<todo-guidance>"));
        assertTrue(prompt.contains("custom guidance text"));
        assertFalse(
                prompt.contains("Do not batch up several todos"), "verbatim override must replace the canned block");
    }

    @Test
    void todoGuidanceDisabledForLiteralFalse() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions",
                "yca.provider.providers.0.model", "m",
                "yca.provider.providers.0.prompt.todo", "false"));
        final var builder = builder(cfg, todoResolver(), providerManager(cfg));
        final var prompt = builder.build();
        assertFalse(prompt.contains("<todo-guidance>"), "false must disable the guidance appendix");
    }

    @Test
    void systemPromptTeachesReadingTheTodoQueue() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(
                prompt.contains("Each request ends with a <session-state> block carrying the current todo list"),
                "the rules must point at the session-state tail as the todo carrier");
        assertTrue(
                prompt.contains("pending items are the task queue - continue with them unless the user redirects"),
                "the rules must order to follow pending items");
        assertTrue(
                prompt.contains("Keep the list accurate with write_todo as work progresses"),
                "the rules must order to keep statuses accurate");
    }

    @Test
    void closedToolsetInvariantPresentInBuildMode() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(
                prompt.contains("only if its exact name appears in the `tools` field"),
                "build mode should teach the closed-toolset invariant");
        assertTrue(prompt.contains("any other name will be rejected"));
    }

    @Test
    void closedToolsetInvariantPresentInPlanMode() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "plan"));
        final var permissions = new PermissionService(cfg);
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg, null, null, permissions, new AgentProvider(cfg), providerManager(cfg), null, null);
        final var prompt = builder.build();
        assertTrue(
                prompt.contains("only if its exact name appears in the `tools` field"),
                "plan mode should also teach the closed-toolset invariant");
        assertTrue(prompt.contains("any other name will be rejected"));
    }

    @Test
    void systemPromptCapShrinksToFitSmallWindow() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.model", "m",
                "yca.provider.providers.0.contextWindow", "8192"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        // 8k window -> usable = 8192 - 8192/4 = 6144; (6144/2)*4 = 12288 char cap
        assertTrue(prompt.length() <= 12_288, "small window must shrink the system prompt, but got " + prompt.length());
        assertFalse(prompt.contains("char budget"), "the assembled prompt should already fit the derived cap");
    }

    @Test
    void systemPromptIsElidedWithMarkerWhenOverGlobalCap() {
        final var cfg = config(Map.of(
                "yca.storageHome",
                storageHome.toString(),
                "yca.workingDir",
                project.toString(),
                "yca.mode",
                "build",
                "yca.llm.systemPromptMaxChars",
                "1200"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(prompt.contains("char budget"), "over-limit prompt should be elided with the marker");
        assertTrue(
                prompt.length() < 2_000,
                "prompt must be capped to roughly the configured budget (tail 1200 + marker), but got "
                        + prompt.length());
        assertTrue(
                prompt.length() > 1_200,
                "the marker is prepended on top of the capped tail, so the result is just over the budget");
        assertFalse(
                prompt.contains("You are yca, a terminal coding agent. You help the user"),
                "the leading preamble should be trimmed, keeping only the tail");
    }

    private static TodoPromptResolver todoResolver() {
        return new TodoPromptResolver();
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, new JsonMapperImpl(List.of(), key -> Optional.empty()));
    }

    private static SystemPromptBuilder builder(
            final CodingAgentConfiguration cfg, final TodoPromptResolver resolver, final ProviderManager manager) {
        return new SystemPromptBuilder(
                cfg, null, null, new PermissionService(cfg), new AgentProvider(cfg), manager, resolver, null);
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
