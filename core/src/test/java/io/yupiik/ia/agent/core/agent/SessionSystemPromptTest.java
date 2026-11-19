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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSystemPromptTest {
    private static final String WORKSPACE_ELISION = "[...leading workspace context elided...]\n";

    @TempDir
    Path home;

    @Test
    void appendAndOverrideAreIsolatedAndKeepWorkspaceGuidanceAndPlanPolicy() throws Exception {
        final var a = Files.createDirectory(home.resolve("A"));
        final var b = Files.createDirectory(home.resolve("B"));
        Files.writeString(a.resolve("AGENTS.md"), "workspace-rule-A");
        Files.writeString(b.resolve("AGENTS.md"), "workspace-rule-B");
        final var props = Map.of(
                "yca.home",
                home.toString(),
                "yca.storageHome",
                home.resolve("storage").toString(),
                "yca.skills.enabled",
                "false",
                "yca.memory.enabled",
                "false");
        final var config = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(props.get(key)))
                .get();
        final var permissions = new PermissionService(config, a);
        permissions.setMode(AgentMode.PLAN);
        permissions.setPermissionMode(PermissionMode.ASK);
        final var append = new SystemPromptBuilder(config, null, null, permissions, null, null, null, null, null, a);
        final var override = new SystemPromptBuilder(config, null, null, permissions, null, null, null, null, null, b);
        append.configureSessionContext(List.of(b), new SessionMetadata.Instructions("append", List.of("client-A")));
        override.configureSessionContext(List.of(), new SessionMetadata.Instructions("override", List.of("client-B")));
        final var first = append.build();
        final var second = override.build();
        assertTrue(first.length() <= append.resolvedSystemPromptCap());
        assertTrue(second.length() <= override.resolvedSystemPromptCap());
        assertFalse(first.contains(WORKSPACE_ELISION));
        assertFalse(second.contains(WORKSPACE_ELISION));
        assertTrue(first.contains("You are yca"));
        assertTrue(first.contains("client-A"));
        assertTrue(first.contains("take precedence on conflict"));
        assertTrue(first.contains("workspace-rule-A"));
        assertTrue(first.contains("Additional workspace directories"));
        assertTrue(first.contains("Directory context grants no access"));
        assertFalse(first.contains("client-B"));
        assertFalse(second.contains("You are yca"));
        assertFalse(second.contains("Tool usage policy:"));
        assertTrue(second.contains("client-B"));
        assertTrue(second.contains("workspace-rule-B"));
        assertTrue(second.contains("Mode: PLAN"));
        assertFalse(second.contains("client-A"));
        assertEquals(AgentMode.PLAN, permissions.mode());
        assertEquals(PermissionMode.ASK, permissions.permissionMode());
        assertTrue(permissions.sessionGrants().isEmpty());
        assertThrows(IllegalStateException.class, () -> append.configureSessionContext(List.of(), null));
        assertEquals(a, append.effectiveWorkdir());
        assertEquals(b, override.effectiveWorkdir());
    }

    @Test
    void acceptedInstructionsSurviveLargeWorkspaceTruncationAndSmallBudgetsRejectUpFront() throws Exception {
        Files.writeString(home.resolve("AGENTS.md"), "workspace-rule-".repeat(3000));
        final var props = new HashMap<>(Map.of(
                "yca.home",
                home.toString(),
                "yca.storageHome",
                home.resolve("storage").toString(),
                "yca.skills.enabled",
                "false",
                "yca.memory.enabled",
                "false",
                "yca.llm.systemPromptMaxChars",
                "24000"));
        final var config = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(props.get(key)))
                .get();
        final var instructions =
                new SessionMetadata.Instructions("append", List.of("client-start-" + "x".repeat(7000) + "-client-end"));
        final var builder = new SystemPromptBuilder(config, null, null, null, null, null, null, null, null, home);
        builder.configureSessionContext(List.of(), instructions);
        final var rendered = builder.build();
        assertTrue(rendered.contains(instructions.text().getFirst()));
        assertTrue(rendered.contains("You are yca"));
        assertTrue(rendered.contains(WORKSPACE_ELISION));
        assertEquals(24_000, rendered.length(), "the elision marker must count towards the cap too");
        props.put("yca.llm.systemPromptMaxChars", "6000");
        final var small = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(props.get(key)))
                .get();
        final var rejected = new SystemPromptBuilder(small, null, null, null, null, null, null, null, null, home);
        assertThrows(IllegalArgumentException.class, () -> rejected.configureSessionContext(List.of(), instructions));
        // Failed setup has not frozen or partially installed the rejected options.
        rejected.configureSessionContext(List.of(), new SessionMetadata.Instructions("override", List.of("short")));
        assertTrue(rejected.build().contains("short"));
        assertFalse(rejected.build().contains("client-start"));
    }

    @Test
    void oversizedPlanPolicyRejectsContextBeforeInstallationEvenWhenStartingInBuild() throws Exception {
        final var extra = Files.createDirectory(home.resolve("extra"));
        final var config = config(Map.of(
                "yca.llm.systemPromptMaxChars",
                "6000",
                "yca.permissions.bash.readOnlyPromptHints",
                "policy-".repeat(1500)));
        for (final var mode : AgentMode.values()) {
            for (final var instructionMode : List.of("append", "override")) {
                final var permissions = new PermissionService(config, home);
                permissions.setMode(mode);
                final var builder = builder(config, permissions);
                final var failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.configureSessionContext(
                                List.of(extra),
                                new SessionMetadata.Instructions(instructionMode, List.of("rejected-client"))));
                assertTrue(failure.getMessage().contains("system prompt budget"));
                // Rejection did not freeze setup, install roots/text, or replace the defaults.
                builder.configureSessionContext(List.of(), null);
                permissions.setMode(AgentMode.BUILD);
                final var rendered = builder.build();
                assertFalse(rendered.contains("rejected-client"));
                assertFalse(rendered.contains("Additional workspace directories"));
                assertEquals(builder(config, permissions).build(), rendered);
            }
        }
    }

    @Test
    void longPlanPolicyAndClientContextFitAcrossModesAndPromptVariants() throws Exception {
        Files.writeString(
                home.resolve("AGENTS.md"), "workspace-front-" + "workspace-rule-".repeat(3000) + "-workspace-tail");
        final var extra = Files.createDirectory(home.resolve("extra"));
        final var hints = "policy-start-" + "h".repeat(10_000) + "-policy-end";
        final var config = config(
                Map.of("yca.llm.systemPromptMaxChars", "24000", "yca.permissions.bash.readOnlyPromptHints", hints));
        for (final var instructionMode : List.of("append", "override")) {
            final var permissions = new PermissionService(config, home);
            permissions.setMode(AgentMode.BUILD);
            final var builder = builder(config, permissions);
            final var clientText = "client-start-" + "c".repeat(500) + "-client-end";
            builder.configureSessionContext(
                    List.of(extra), new SessionMetadata.Instructions(instructionMode, List.of(clientText)));
            for (final var mode : AgentMode.values()) {
                permissions.setMode(mode);
                for (final var request :
                        new String[] {null, "explain the code", "fix the code", "how do I fix the code?"}) {
                    final var rendered = builder.build(request);
                    assertEquals(24_000, rendered.length(), "all retained blocks and the marker must fit");
                    assertTrue(rendered.contains(WORKSPACE_ELISION));
                    assertTrue(rendered.contains(clientText));
                    assertTrue(rendered.contains("- " + extra + '\n'));
                    assertTrue(rendered.contains("Relative paths still resolve against " + home));
                    assertTrue(rendered.contains("Directory context grants no access"));
                    assertFalse(rendered.contains("workspace-front"));
                    assertTrue(rendered.contains("workspace-tail"), "workspace truncation must still keep the tail");
                    assertEquals("append".equals(instructionMode), rendered.startsWith("You are yca"));
                    assertEquals(mode == AgentMode.PLAN, rendered.contains(hints));
                    assertEquals(mode == AgentMode.PLAN, rendered.contains("Repository mutations are forbidden"));
                    if (mode == AgentMode.PLAN) {
                        if ("fix the code".equals(request)) {
                            assertTrue(rendered.contains(
                                    "Analyze the codebase and deliver a detailed, concrete implementation plan"));
                            assertTrue(
                                    rendered.endsWith(
                                            "Completed or cancelled items from earlier build sessions are progress history: keep them.\n"));
                        } else {
                            assertTrue(
                                    rendered.endsWith(
                                            "The request is informational: explore the codebase as needed and answer directly - no plan or todo list is required.\n"));
                        }
                    }
                }
            }
        }
    }

    @Test
    void exactRetainedBoundaryCountsHeadersRootExplanationDefaultsPolicyAndMarker() throws Exception {
        final var extra = Files.createDirectory(home.resolve("extra"));
        final var hints = "policy-start-" + "h".repeat(10_000) + "-policy-end";
        for (final var instructions : new SessionMetadata.Instructions[] {
            new SessionMetadata.Instructions("append", List.of("first", "", "last")),
            new SessionMetadata.Instructions("override", List.of("first", "", "last")),
            null
        }) {
            final var wide = config(
                    Map.of("yca.llm.systemPromptMaxChars", "64000", "yca.permissions.bash.readOnlyPromptHints", hints));
            final var permissions = new PermissionService(wide, home);
            permissions.setMode(AgentMode.PLAN);
            final var reference = builder(wide, permissions);
            reference.configureSessionContext(List.of(extra), instructions);
            final var full = reference.build("fix the code");
            final var defaultsSize = full.indexOf("Environment:\n");
            final var contextStart = full.indexOf("\nAdditional workspace directories (context only):\n");
            assertTrue(defaultsSize >= 0 && contextStart > defaultsSize);
            final var required = defaultsSize + full.length() - contextStart + WORKSPACE_ELISION.length();
            final var tight = config(Map.of(
                    "yca.llm.systemPromptMaxChars",
                    Integer.toString(required),
                    "yca.permissions.bash.readOnlyPromptHints",
                    hints));
            final var exact = builder(tight, permissions);
            exact.configureSessionContext(List.of(extra), instructions);
            final var rendered = exact.build("fix the code");
            assertEquals(required, rendered.length());
            assertEquals(
                    full.substring(0, defaultsSize) + WORKSPACE_ELISION + full.substring(contextStart),
                    rendered,
                    "at the exact boundary only workspace text may be dropped, including its last character");
            final var tooSmall = config(Map.of(
                    "yca.llm.systemPromptMaxChars",
                    Integer.toString(required - 1),
                    "yca.permissions.bash.readOnlyPromptHints",
                    hints));
            final var rejected = builder(tooSmall, permissions);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> rejected.configureSessionContext(List.of(extra), instructions));
            // Failed validation leaves even a root-only setup retryable.
            rejected.configureSessionContext(List.of(), null);
        }
    }

    @Test
    void emptyClientContextKeepsLegacyCappingAndShortPromptsRemainUnelided() {
        for (final var cap : List.of("1200", "32000")) {
            final var config = config(Map.of("yca.llm.systemPromptMaxChars", cap));
            final var permissions = new PermissionService(config, home);
            for (final var mode : AgentMode.values()) {
                permissions.setMode(mode);
                final var unconfigured = builder(config, permissions);
                final var configured = builder(config, permissions);
                configured.configureSessionContext(List.of(), null);
                final var expected = unconfigured.build("fix the code");
                assertEquals(expected, configured.build("fix the code"));
                if ("1200".equals(cap)) {
                    assertTrue(expected.startsWith("[...system prompt exceeded the 1200 char budget;"));
                    assertTrue(expected.length() > 1200, "legacy marker overhead is unchanged");
                } else {
                    assertTrue(expected.startsWith("You are yca"));
                    assertFalse(expected.contains("elided"));
                }
            }
        }
    }

    private SystemPromptBuilder builder(final CodingAgentConfiguration config, final PermissionService permissions) {
        return new SystemPromptBuilder(
                config, null, null, permissions, null, null, new TodoPromptResolver(), null, null, home);
    }

    private CodingAgentConfiguration config(final Map<String, String> overrides) {
        final var props = new HashMap<String, String>();
        props.put("yca.home", home.toString());
        props.put("yca.storageHome", home.resolve("storage").toString());
        props.put("yca.skills.enabled", "false");
        props.put("yca.memory.enabled", "false");
        props.putAll(overrides);
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(props.get(key)))
                .get();
    }
}
