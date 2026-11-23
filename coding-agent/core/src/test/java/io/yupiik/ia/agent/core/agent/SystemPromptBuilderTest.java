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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationLoader;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.skills.SkillLocator;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SystemPromptBuilderTest {

    @TempDir
    Path storageHome;

    @TempDir
    Path project;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

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
                null,
                null,
                (config(Map.of(
                                "yca.home", fakeHome.toString(),
                                "yca.storageHome", storageAgents.getParent().toString(),
                                "yca.workingDir", nested.toString(),
                                "yca.skills.enabled", "true")))
                        .resolveWorkingDir());
        final var prompt = builder.build();
        assertTrue(prompt.contains("nested rule"));
        assertFalse(
                prompt.contains("project rule"),
                "only the nearest project AGENTS.md should be loaded, not the full ancestor stack");
        assertTrue(
                prompt.contains("storage rule"),
                "storage-home AGENTS.md should take precedence over other global files");
        assertFalse(prompt.contains("always use tabs"), "only the first existing global AGENTS.md should be loaded");
    }

    @Test
    void skillsLocatorIncludesAgentsGlobalDir() {
        final var cfg = config(Map.of());
        final var dirs = SkillLocator.skillDirectories(cfg, cfg.resolveWorkingDir());
        assertTrue(
                dirs.contains(Path.of(cfg.resolveHome(), ".agents", "skills")),
                "~/.agents/skills should be a global skill directory: " + dirs);
    }

    @Test
    void skillBodiesAreNotInjectedButTheirCatalogIsInTheSessionPrompt() throws Exception {
        final var skill = storageHome.resolve("skills").resolve("my-skill").resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: custom-name\ndescription: a skill\n---\nUse the skill body here.");
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.skills.enabled", "true"));
        final var builder =
                new SystemPromptBuilder(cfg, null, null, null, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        assertFalse(
                prompt.contains("<skill name=\"custom-name\">"),
                "no skill block should be inlined in the system prompt (mirroring opencode)");
        assertFalse(
                prompt.contains("Use the skill body here."),
                "skill bodies should never be inlined; they load on demand via the skill tool");
        final var catalog = SkillLocator.availableSkillsCatalog(cfg, cfg.resolveWorkingDir());
        assertTrue(prompt.contains(catalog), "metadata must be available in this session's prompt");
        assertTrue(catalog.contains("<skill><name>custom-name</name>"), "catalog should list the skill: " + catalog);
        assertTrue(
                catalog.contains("<description>a skill</description>"),
                "catalog should carry the description: " + catalog);
    }

    @Test
    void projectSkillBodiesAreNotInjectedEither() throws Exception {
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
        final var builder =
                new SystemPromptBuilder(cfg, null, null, null, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        assertFalse(
                prompt.contains("<skill name=\"proj-skill\">"),
                "no skill block should be inlined in the system prompt");
        assertTrue(
                SkillLocator.availableSkillsCatalog(cfg, cfg.resolveWorkingDir())
                        .contains("<skill><name>proj-skill</name>"),
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
        final var builder =
                new SystemPromptBuilder(cfg, null, null, null, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        assertFalse(prompt.contains("<skill name=\"my-skill\">"));
        assertFalse(prompt.contains("<available_skills>"));
    }

    @Test
    void skillsAndInstructionsAreIsolatedAndCatalogsStayFrozenForEachSession() throws Exception {
        final var first = Files.createDirectories(project.resolve("first"));
        final var second = Files.createDirectories(project.resolve("second"));
        for (final var root : List.of(first, second)) {
            final var name = root.getFileName().toString();
            Files.writeString(root.resolve("AGENTS.md"), name + "-instructions");
            final var skill = root.resolve(".yca/skills/" + name + "/SKILL.md");
            Files.createDirectories(skill.getParent());
            Files.writeString(skill, "---\nname: " + name + "\ndescription: " + name + "-metadata\n---\nsecret-body");
        }
        final var cfg = config(Map.of(
                "yca.home", storageHome.resolve("home").toString(),
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", first.toString(),
                "yca.skills.enabled", "true"));
        final var a = new SystemPromptBuilder(cfg, null, null, null, null, null, null, null, null, first);
        final var b = new SystemPromptBuilder(cfg, null, null, null, null, null, null, null, null, second);

        final var promptA = a.build();
        final var promptB = b.build();
        assertTrue(promptA.contains("first-instructions") && promptA.contains("first-metadata"), promptA);
        assertFalse(promptA.contains("second-instructions") || promptA.contains("second-metadata"), promptA);
        assertTrue(promptB.contains("second-instructions") && promptB.contains("second-metadata"), promptB);
        assertFalse(promptB.contains("first-instructions") || promptB.contains("first-metadata"), promptB);
        assertFalse(promptA.contains("secret-body") || promptB.contains("secret-body"));
        Files.writeString(first.resolve(".yca/skills/first/SKILL.md"), "description changed mid-session");
        assertEquals(promptA, a.build(), "metadata changes must not rewrite the session's stable prefix");
        assertEquals(promptB, b.build());
    }

    @Test
    void explicitSessionRootOverridesConfiguredWorkingAndProjectDirectories() {
        final var working = project.resolve("launch");
        final var target = project.resolve("target");
        final var cfg = config(Map.of("yca.workingDir", working.toString(), "yca.projectDir", target.toString()));
        final var builder = new SystemPromptBuilder(cfg, null, null, null, null, null, null, null, null, target);

        final var prompt = builder.build();

        assertTrue(prompt.contains("- Working directory: " + target + "\n"), prompt);
        assertFalse(prompt.contains("- Working directory: " + working + "\n"), prompt);
    }

    @Test
    void volatileSessionStateIsOutsideTheSystemPrompt(final JsonMapper mapper) throws Exception {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString()));
        final var todos = new TodoStore(cfg, mapper);
        todos.replace(List.of(new TodoItem("step one", "pending")));
        final var builder = new SystemPromptBuilder(
                cfg, null, todos, null, null, null, null, null, null, (cfg).resolveWorkingDir());
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
    void memoryLivesInTheStablePrefixNotInTheSessionState() throws Exception {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString()));
        final var memory = new MemoryStore(cfg);
        memory.append(true, List.of("- a durable global fact"), cfg.resolveWorkingDir());
        final var builder = new SystemPromptBuilder(
                cfg, memory, null, null, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        assertTrue(
                prompt.contains("<memory>") && prompt.contains("a durable global fact"),
                "memory must be part of the stable system prefix (built once per turn): " + prompt);
        final var state = builder.sessionState(null);
        assertFalse(
                state.contains("<memory>"),
                "the volatile session state must no longer carry the memory section: " + state);
        assertFalse(
                state.contains("a durable global fact"),
                "memory facts must not leak into the per-round tail: " + state);
    }

    @Test
    void memoryIsDeduplicatedInsideTheSection() throws Exception {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString()));
        final var memory = new MemoryStore(cfg);
        memory.append(true, List.of("- fact one", "- fact one", "- fact two"), cfg.resolveWorkingDir());
        final var builder = new SystemPromptBuilder(
                cfg, memory, null, null, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        final var section = prompt.substring(prompt.indexOf("<memory>"), prompt.indexOf("</memory>"));
        assertEquals(1, occurrences(section, "- fact one"), "duplicated bullets must be deduplicated: " + section);
        assertEquals(1, occurrences(section, "- fact two"), "distinct bullets must be kept: " + section);
    }

    @Test
    void memorySectionIsAFrozenSessionSnapshotAcrossBuilds() throws Exception {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString()));
        final var memory = new MemoryStore(cfg);
        memory.append(true, List.of("- a durable global fact"), cfg.resolveWorkingDir());
        final var builder = new SystemPromptBuilder(
                cfg, memory, null, null, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var first = builder.build();
        // a mid-session memory_write mutates MEMORY.md but must not rewrite the frozen prompt snapshot
        memory.append(true, List.of("- a fact added mid-session"), cfg.resolveWorkingDir());
        final var second = builder.build();
        assertEquals(
                memorySection(first),
                memorySection(second),
                "the <memory> section must stay byte-identical across turns despite a mid-session write");
        assertTrue(first.contains("a durable global fact"), "the snapshot carries the session-start fact");
        assertFalse(
                first.contains("a fact added mid-session"),
                "a mid-session write must not leak into the frozen snapshot: " + first);
    }

    private static String memorySection(final String prompt) {
        final var start = prompt.indexOf("<memory>");
        final var end = prompt.indexOf("</memory>");
        return start < 0 || end < 0 ? "" : prompt.substring(start, end + "</memory>".length());
    }

    private static int occurrences(final String haystack, final String needle) {
        var out = 0;
        var from = 0;
        while (true) {
            final var i = haystack.indexOf(needle, from);
            if (i < 0) {
                return out;
            }
            out++;
            from = i + needle.length();
        }
    }

    @Test
    void sessionStateReportsTheCurrentMode() throws Exception {
        final var cfg = config(Map.of("yca.mode", "plan"));
        final var permissions = new PermissionService(cfg, (cfg).resolveWorkingDir());
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg, null, null, permissions, null, null, null, null, null, (cfg).resolveWorkingDir());
        assertTrue(
                builder.sessionState(null).contains("Mode: PLAN"), "plan mode must be reported in the volatile tail");

        permissions.setMode(AgentMode.BUILD);
        final var state = builder.sessionState(null);
        assertTrue(
                state.contains("Mode: BUILD"),
                "build mode must be reported in the volatile tail, switching from plan: " + state);
        assertFalse(
                state.contains("if this turn changes the plan, call write_todo"),
                "the plan-mode sync reminder must not leak into build mode: " + state);
    }

    @Test
    void sessionStateRemindsSyncingTodosInPlanMode() throws Exception {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "plan"));
        final var permissions = new PermissionService(cfg, (cfg).resolveWorkingDir());
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg, null, null, permissions, null, null, null, null, null, (cfg).resolveWorkingDir());
        final var state = builder.sessionState(null);
        assertTrue(
                state.contains(
                        "Plan mode: if this turn changes the plan, call write_todo to sync the todo list before answering."),
                "plan mode must carry the per-turn sync reminder in the volatile tail: " + state);
        assertTrue(
                state.indexOf("Plan mode: if this turn changes the plan") > state.indexOf("Mode: PLAN"),
                "the reminder must come after the mode line: " + state);
    }

    @Test
    void planModeStatesReadOnlyAndPlanDelivery() throws Exception {
        final var cfg = config(Map.of("yca.mode", "plan"));
        final var permissions = new PermissionService(cfg, (cfg).resolveWorkingDir());
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg,
                null,
                null,
                permissions,
                new AgentProvider(cfg),
                null,
                null,
                null,
                null,
                (cfg).resolveWorkingDir());
        final var prompt = builder.build("add a retry to the LLM client and fix its error handling");
        assertTrue(prompt.contains("Mode: PLAN"));
        assertTrue(prompt.contains("Repository mutations are forbidden by default"));
        assertTrue(prompt.contains("write_todo is the only state-changing tool allowed"));
        assertTrue(prompt.contains("every item stays 'pending'"));
        assertTrue(prompt.contains("statuses only advance in build mode"));
        assertTrue(prompt.contains("ready for the user to review"));
        assertTrue(
                prompt.contains("keep the list in sync with the discussion"),
                "the plan tail must order re-syncing todos on discussion changes: " + prompt);
        assertTrue(
                prompt.contains(
                        "Completed or cancelled items from earlier build sessions are progress history: keep them."),
                "the plan tail must order keeping the progress history: " + prompt);
        // the mode constraint is a tail: the mode-neutral identity (which mentions editing) comes first
        assertTrue(prompt.indexOf("edit files") < prompt.indexOf("Mode: PLAN"));
        // the system prompt is deterministic now: no volatile session state inside
        assertEquals(prompt, builder.build("add a retry to the LLM client and fix its error handling"));
    }

    @Test
    void planModeAnswersInformationalQuestionsDirectly() throws Exception {
        final var cfg = config(Map.of("yca.mode", "plan"));
        final var permissions = new PermissionService(cfg, (cfg).resolveWorkingDir());
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg,
                null,
                null,
                permissions,
                new AgentProvider(cfg),
                null,
                null,
                null,
                null,
                (cfg).resolveWorkingDir());
        final var prompt = builder.build("Explain what this project is about: purpose, architecture and how to start");
        assertTrue(prompt.contains("Mode: PLAN"));
        assertTrue(
                prompt.contains("The request is informational: explore the codebase as needed and answer directly"),
                "informational prompts must not get the plan/todo mandate: " + prompt);
        assertFalse(
                prompt.contains("deliver a detailed, concrete implementation plan"),
                "no plan deliverable for informational prompts: " + prompt);
        assertTrue(prompt.contains("write_todo is the only state-changing tool allowed"), "read-only rule stays");
    }

    @Test
    void explicitProjectRootScopesContextAndPersona() throws Exception {
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
                null,
                null,
                target);
        final var prompt = builder.build();
        assertTrue(
                prompt.contains("Working directory: " + target.toAbsolutePath().normalize()),
                "persona should name the explicit target project root");
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
                null,
                null,
                (config(Map.of(
                                "yca.storageHome", storageHome.toString(),
                                "yca.workingDir", project.toString(),
                                "yca.skills.enabled", "true")))
                        .resolveWorkingDir());
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
                null,
                null,
                (config(Map.of(
                                "yca.storageHome", storageHome.toString(),
                                "yca.workingDir", project.toString(),
                                "yca.skills.enabled", "true")))
                        .resolveWorkingDir());
        final var prompt = builder.build("explain how the retry logic works");
        assertFalse(
                prompt.contains("owner docs"),
                "project README must not be eagerly inlined (opencode-aligned: read project docs on demand)");
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
        assertTrue(
                prompt.contains("Combine todo updates with substantive tool calls"),
                "default family should map to the OpenAI block");
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
    void todoGuidancePlanModeUsesThePlanVariant() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "plan",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl", "https://mystery.example/v1",
                "yca.provider.providers.0.model", "m"));
        final var permissions = new PermissionService(cfg, (cfg).resolveWorkingDir());
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg,
                null,
                null,
                permissions,
                new AgentProvider(cfg),
                providerManager(cfg),
                todoResolver(),
                null,
                null,
                (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        assertTrue(
                prompt.contains("Task Management (plan mode)"),
                "plan mode must inject the plan variant of the guidance: " + prompt);
        assertFalse(
                prompt.contains("Combine todo updates with substantive tool calls"),
                "plan mode must not inject the build completion wording: " + prompt);

        permissions.setMode(AgentMode.BUILD);
        final var buildPrompt = builder.build();
        assertTrue(
                buildPrompt.contains("Combine todo updates with substantive tool calls"),
                "build mode must inject the completion wording: " + buildPrompt);
        assertFalse(
                buildPrompt.contains("Task Management (plan mode)"),
                "build mode must not inject the plan variant: " + buildPrompt);
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
                prompt.contains("Combine todo updates with substantive tool calls"),
                "verbatim override must replace the canned block");
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

    /** A provider configuring no guidance inherits {@code yca.todos.guidance}. */
    @Test
    void todoGuidanceFallsBackToTheGlobalOne() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.todos.guidance", "never stop with an open todo",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl",
                        "http://10." + ThreadLocalRandom.current().nextInt(256) + "."
                                + ThreadLocalRandom.current().nextInt(256) + "."
                                + ThreadLocalRandom.current().nextInt(1, 255) + ":8000/v1",
                "yca.provider.providers.0.model", "qwen3.6-27b"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(prompt.contains("<todo-guidance>"));
        assertTrue(prompt.contains("never stop with an open todo"));
        assertFalse(
                prompt.contains("Combine todo updates with substantive tool calls"),
                "the global guidance replaces the family block of a provider that configures none");
    }

    /** A provider configuring its own guidance keeps it: the global one is a fallback, not an override. */
    @Test
    void todoGuidanceProviderValueWinsOverTheGlobalOne() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.todos.guidance", "global guidance",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.baseUrl",
                        "http://10." + ThreadLocalRandom.current().nextInt(256) + "."
                                + ThreadLocalRandom.current().nextInt(256) + "."
                                + ThreadLocalRandom.current().nextInt(1, 255) + ":8000/v1",
                "yca.provider.providers.0.model", "qwen3.6-27b",
                "yca.provider.providers.0.prompt.todo", "provider guidance"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(prompt.contains("provider guidance"));
        assertFalse(prompt.contains("global guidance"), "the provider value must win");
    }

    @Test
    void systemPromptTeachesReadingTheTodoQueue() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(
                prompt.contains(
                        "The conversation carries <session-state> blocks with the current todo list, mode, date and memory"),
                "the rules must point at the session-state tail as the todo carrier");
        assertTrue(
                prompt.contains("the most recent one is authoritative"),
                "the rules must teach that a request may end without a refreshed tail when nothing changed");
        assertTrue(
                prompt.contains("pending items are the task queue - continue with them unless the user redirects"),
                "the rules must order to follow pending items");
        assertTrue(
                prompt.contains("Keep the list accurate with write_todo as work progresses"),
                "the rules must order to keep statuses accurate");
    }

    @Test
    void systemPromptTeachesMaximalParallelToolBatching() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString()));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(
                prompt.contains("Maximize use of parallel tool calls"),
                "the tool policy must tell the model to maximize parallel batching: " + prompt);
        assertTrue(
                prompt.contains("read .` + `glob **/*.md"),
                "a worked round-1 batching pattern must be present so the model batches from the start: " + prompt);
        assertTrue(
                prompt.contains("targeted content search that returns useful source and test context"),
                "the tool policy must encourage actionable discovery without skipping necessary reads: " + prompt);
        assertFalse(
                prompt.contains("batched read-only survey"),
                "the chaining survey invite must not be advertised (it taught the noisy ls -la && echo form)");
    }

    @Test
    void systemPromptTeachesCompressionWhenEnabled() {
        final var cfg = config(Map.of(
                "yca.storageHome",
                storageHome.toString(),
                "yca.workingDir",
                project.toString(),
                "yca.mode",
                "build",
                "yca.tools.manageContext.enabled",
                "true"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertTrue(
                prompt.contains("The ONLY tool you have for context management is `compress`"),
                "with compression enabled the rules must teach proactive context management");
        assertTrue(
                prompt.contains("proactive working-context targets, not model capacity"),
                "the rules must distinguish working-context targets from model capacity");
        assertTrue(
                prompt.contains("if no safe closed range exists, keep working"),
                "the rules must allow deferring compression when no closed range is safe");
    }

    @Test
    void systemPromptOmitsCompressionRuleWhenDisabled() {
        final var compressionOff = config(Map.of(
                "yca.storageHome",
                storageHome.toString(),
                "yca.workingDir",
                project.toString(),
                "yca.mode",
                "build",
                "yca.compression.enabled",
                "false"));
        assertFalse(
                builder(compressionOff, todoResolver(), providerManager(compressionOff))
                        .build()
                        .contains("The ONLY tool you have for context management"),
                "no need to push compression when it is disabled");

        final var toolOff = config(Map.of(
                "yca.storageHome",
                storageHome.toString(),
                "yca.workingDir",
                project.toString(),
                "yca.mode",
                "build",
                "yca.tools.manageContext.enabled",
                "false"));
        assertFalse(
                builder(toolOff, todoResolver(), providerManager(toolOff))
                        .build()
                        .contains("The ONLY tool you have for context management"),
                "must not mention a disabled tool");
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
        final var permissions = new PermissionService(cfg, (cfg).resolveWorkingDir());
        permissions.setMode(AgentMode.PLAN);
        final var builder = new SystemPromptBuilder(
                cfg,
                null,
                null,
                permissions,
                new AgentProvider(cfg),
                providerManager(cfg),
                null,
                null,
                null,
                (cfg).resolveWorkingDir());
        final var prompt = builder.build();
        assertTrue(
                prompt.contains("only if its exact name appears in the `tools` field"),
                "plan mode should also teach the closed-toolset invariant");
        assertTrue(prompt.contains("any other name will be rejected"));
    }

    @Test
    void planModeInjectsTheReadOnlyBashHintsDefault() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "plan"));
        final var svc = new PermissionService(cfg, (cfg).resolveWorkingDir());
        svc.setMode(AgentMode.PLAN);
        final var prompt = new SystemPromptBuilder(
                        cfg,
                        null,
                        null,
                        svc,
                        new AgentProvider(cfg),
                        providerManager(cfg),
                        todoResolver(),
                        null,
                        null,
                        (cfg).resolveWorkingDir())
                .build();
        assertTrue(
                prompt.contains("read-only command families"),
                "the plan-mode tail should inject the configured bash hints: " + prompt);
        assertTrue(
                prompt.contains("such as git add are denied"), "the default hints should mention git add: " + prompt);
    }

    @Test
    void planModeInjectsCustomReadOnlyBashHints() {
        final var cfg = config(Map.of(
                "yca.storageHome",
                storageHome.toString(),
                "yca.workingDir",
                project.toString(),
                "yca.mode",
                "plan",
                "yca.permissions.bash.readOnlyPromptHints",
                "only git status and git diff are safe"));
        final var svc = new PermissionService(cfg, (cfg).resolveWorkingDir());
        svc.setMode(AgentMode.PLAN);
        final var prompt = new SystemPromptBuilder(
                        cfg,
                        null,
                        null,
                        svc,
                        new AgentProvider(cfg),
                        providerManager(cfg),
                        todoResolver(),
                        null,
                        null,
                        (cfg).resolveWorkingDir())
                .build();
        assertTrue(prompt.contains("only git status and git diff are safe"));
        assertFalse(prompt.contains("such as git add"), "a custom value must replace the default sentence");
    }

    @Test
    void planModeOmitsReadOnlyBashHintsWhenBlank() {
        final var cfg = config(Map.of(
                "yca.storageHome",
                storageHome.toString(),
                "yca.workingDir",
                project.toString(),
                "yca.mode",
                "plan",
                "yca.permissions.bash.readOnlyPromptHints",
                ""));
        final var svc = new PermissionService(cfg, (cfg).resolveWorkingDir());
        svc.setMode(AgentMode.PLAN);
        final var prompt = new SystemPromptBuilder(
                        cfg,
                        null,
                        null,
                        svc,
                        new AgentProvider(cfg),
                        providerManager(cfg),
                        todoResolver(),
                        null,
                        null,
                        (cfg).resolveWorkingDir())
                .build();
        assertFalse(
                prompt.contains("read-only command families"),
                "a blank hint must omit the sentence entirely: " + prompt);
    }

    @Test
    void buildModeOmitsTheReadOnlyBashHints() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build"));
        final var prompt = builder(cfg, todoResolver(), providerManager(cfg)).build();
        assertFalse(prompt.contains("read-only command families"), "the bash hints are plan-mode only: " + prompt);
    }

    @Test
    void systemPromptCapShrinksToFitSmallWindow() {
        final var cfg = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.model", "m",
                "yca.provider.providers.0.contextWindow", "8192",
                "yca.llm.systemPromptMaxChars", "0"));
        final var promptBuilder = builder(cfg, todoResolver(), providerManager(cfg));
        // 8k window -> usable = 8192 - 8192/4 = 6144; (6144/2)*4 = 12288 char cap
        assertEquals(12_288, promptBuilder.resolvedSystemPromptCap());
        final var prompt = promptBuilder.build();
        assertTrue(prompt.length() <= 12_288, "small window must shrink the system prompt, but got " + prompt.length());
        assertFalse(prompt.contains("char budget"), "the assembled prompt should already fit the derived cap");
    }

    @Test
    void systemPromptCapFollowsTheDeclaredOutputBudget() {
        // 8k window declaring 3k output tokens -> usable = 5192; (5192/2)*4 = 10384 char cap
        final var withCap = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.model", "m",
                "yca.provider.providers.0.contextWindow", "8192",
                "yca.provider.providers.0.outputTokens", "3000",
                "yca.llm.systemPromptMaxChars", "0"));
        assertEquals(
                10_384,
                builder(withCap, todoResolver(), providerManager(withCap)).resolvedSystemPromptCap(),
                "the declared output cap must be reserved from the window");
        // no declared output cap -> historical min(20000, window/4) reserve: 8192 - 2048 = 6144; (6144/2)*4
        final var withoutCap = config(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", project.toString(),
                "yca.mode", "build",
                "yca.provider.providers.length", "1",
                "yca.provider.providers.0.model", "m",
                "yca.provider.providers.0.contextWindow", "8192",
                "yca.llm.systemPromptMaxChars", "0"));
        assertEquals(
                12_288,
                builder(withoutCap, todoResolver(), providerManager(withoutCap)).resolvedSystemPromptCap());
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

    @Test
    void sessionContextValidationUsesCandidateCapsAndSessionDefaultsWithoutInstallingRejectedSelections()
            throws Exception {
        Files.writeString(project.resolve("AGENTS.md"), "workspace-retained");
        final var hints = "policy-start-" + "h".repeat(10_000) + "-policy-end";
        final var overrides = new HashMap<String, String>();
        overrides.put("yca.storageHome", storageHome.toString());
        overrides.put("yca.workingDir", project.toString());
        overrides.put("yca.skills.enabled", "false");
        overrides.put("yca.llm.systemPromptMaxChars", "24000");
        overrides.put("yca.permissions.bash.readOnlyPromptHints", hints);
        overrides.put("yca.provider.providers.length", "2");
        overrides.put("yca.provider.providers.0.id", "process");
        overrides.put("yca.provider.providers.0.baseUrl", "https://process.example.invalid/v1");
        overrides.put("yca.provider.providers.0.model", "large");
        overrides.put("yca.provider.providers.0.prompt.todo", "process-guidance-" + "p".repeat(18_000));
        overrides.put("yca.provider.providers.1.id", "session");
        overrides.put("yca.provider.providers.1.baseUrl", "https://session.example.invalid/v1");
        overrides.put("yca.provider.providers.1.model", "large");
        overrides.put("yca.provider.providers.1.prompt.todo", "session-guidance");
        overrides.put("yca.provider.providers.1.models.length", "2");
        overrides.put("yca.provider.providers.1.models.0.model", "large");
        overrides.put("yca.provider.providers.1.models.0.behavior.systemPromptMaxChars", "24000");
        overrides.put("yca.provider.providers.1.models.1.model", "small");
        overrides.put("yca.provider.providers.1.models.1.behavior.systemPromptMaxChars", "6000");
        final var cfg = config(overrides);
        final var manager = providerManager(cfg);
        final var processProvider = manager.current();
        final var accepted = new ChatSettings("session", "large", null, null);
        final var small = accepted.withModel("small");
        final var process = new ChatSettings("process", "large", null, null);
        assertEquals("process", processProvider.id());
        for (final var instructionMode : List.of("append", "override")) {
            final var permissions = new PermissionService(cfg, project);
            permissions.setMode(AgentMode.BUILD);
            final var builder = new SystemPromptBuilder(
                    cfg, null, null, permissions, null, manager, todoResolver(), null, null, project);
            final var instructions = new SessionMetadata.Instructions(instructionMode, List.of("accepted-client"));
            if ("append".equals(instructionMode)) {
                // Huge defaults for another provider are real retained text, not a fixed setup allowance.
                builder.attach(process);
                assertThrows(
                        IllegalArgumentException.class, () -> builder.configureSessionContext(List.of(), instructions));
            }
            builder.attach(accepted);
            builder.configureSessionContext(List.of(), instructions);
            final var before = builder.build("explain the code");
            assertTrue(before.contains("accepted-client"));
            assertTrue(before.contains("workspace-retained"));
            assertEquals("append".equals(instructionMode), before.contains("session-guidance"));
            assertFalse(before.contains("process-guidance"));
            final var failure = assertThrows(IllegalArgumentException.class, () -> builder.validateChatSettings(small));
            assertTrue(failure.getMessage().contains("Selected model cannot accommodate"));
            if ("append".equals(instructionMode)) {
                assertThrows(IllegalArgumentException.class, () -> builder.validateChatSettings(process));
            } else {
                assertDoesNotThrow(
                        () -> builder.validateChatSettings(process), "override must not reserve omitted defaults");
            }
            assertDoesNotThrow(() -> builder.validateChatSettings(accepted));
            assertEquals(24_000, builder.resolvedSystemPromptCap());
            assertEquals(
                    before,
                    builder.build("explain the code"),
                    "validation must not change accepted settings or context");
            permissions.setMode(AgentMode.PLAN);
            for (final var request : List.of("explain the code", "fix the code")) {
                final var rendered = builder.build(request);
                assertTrue(rendered.length() <= builder.resolvedSystemPromptCap());
                assertTrue(rendered.contains("accepted-client"));
                assertTrue(rendered.contains(hints));
                assertEquals("append".equals(instructionMode), rendered.contains("session-guidance"));
                assertFalse(rendered.contains("process-guidance"));
            }
            assertEquals(project, builder.effectiveWorkdir());
            assertEquals(processProvider, manager.current(), "session validation must not select a global provider");
        }
    }

    private static TodoPromptResolver todoResolver() {
        return new TodoPromptResolver();
    }

    private ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    private SystemPromptBuilder builder(
            final CodingAgentConfiguration cfg, final TodoPromptResolver resolver, final ProviderManager manager) {
        return new SystemPromptBuilder(
                cfg,
                null,
                null,
                new PermissionService(cfg, (cfg).resolveWorkingDir()),
                new AgentProvider(cfg),
                manager,
                resolver,
                null,
                null,
                (cfg).resolveWorkingDir());
    }

    /**
     * Builds a configuration from {@code overrides} on top of hermetic defaults: the user home is
     * pinned under the test temp dir so the global AGENTS.md/skills are never read from the real
     * {@code user.home} - a machine with a large {@code ~/.config/opencode/AGENTS.md} used to blow
     * the small-window system-prompt cap test ("small window must shrink the system prompt").
     * Callers may still override {@code yca.home} explicitly (e.g. to test global-file resolution).
     */
    private CodingAgentConfiguration config(final Map<String, String> overrides) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", "~/.yupiik/coding-agent");
        map.put("yca.workingDir", ".");
        map.put("yca.skills.enabled", "true");
        map.putIfAbsent("yca.home", storageHome.resolve("home").toString());
        map.putAll(overrides);
        return ConfigurationLoader.load(CodingAgentConfiguration.class, key -> Optional.ofNullable(map.get(key)));
    }
}
