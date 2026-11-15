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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SubagentRoutePrivacyTest {
    @TempDir
    Path home;

    private JsonMapper mapper;

    @BeforeEach
    void mapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Test
    void secureDefaultFollowsExactLiveSessionRouteNotDeclaringOrProcessProvider() {
        final var f = new Fixture(Map.of("yca.provider.providers.1.subagents.defaultModel", "$external/alt"));
        final var ctx = f.context("private", "shared");
        final var catalog = f.catalog(ctx);
        assertTrue(catalog.success(), catalog.output());
        assertTrue(catalog.output().contains("$private/shared"), catalog.output());
        assertTrue(catalog.output().contains("\"total\":1"), catalog.output());
        assertFalse(catalog.output().contains("external"), catalog.output());
        assertFalse(catalog.output().contains("reviewer"), catalog.output());
        assertTrue(f.spawn(Map.of(), ctx).success());
        assertEquals("$private/shared", f.started.getFirst().admittedModel().routedReference());
        assertTrue(f.spawn(Map.of(), f.context("private", "unlisted")).success());
        assertEquals("$private/unlisted", f.started.getLast().admittedModel().routedReference());
        final var unlisted = f.catalog(f.context("private", "unlisted")).output();
        assertTrue(unlisted.contains("$private/unlisted"), unlisted);
        assertFalse(unlisted.contains("$private/shared"), unlisted);
        final var switched = f.catalog(f.context("external", "alt")).output();
        assertTrue(switched.contains("$external/alt"), switched);
        assertFalse(switched.contains("$private/shared"), switched);
    }

    @Test
    void explicitAlternativeRequiresDedicatedConsentAndSessionAnswerIsReused() {
        final var f = new Fixture(Map.of());
        final var ctx = f.context("private", "shared");
        f.permissions.grantSession("spawn_subagent");
        assertFalse(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertTrue(f.started.isEmpty());
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            assertTrue(f.started.isEmpty(), "no child may be admitted before consent");
            assertTrue(title.contains("$external/shared"));
            assertTrue(detail.contains("$private/shared"));
            assertTrue(detail.contains("project context"));
            assertEquals("subagent_model_route", tool);
            assertEquals("$external/shared", args.get("destination"));
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertEquals(1, prompts.get());
        assertTrue(f.catalog(ctx).output().contains("$external/shared"));
        assertFalse(f.catalog(ctx).output().contains("$external/alt"));
        assertEquals(
                "$external/shared",
                f.started.getFirst().admittedModel().settings().model());
    }

    @Test
    void allowOnceDoesNotBecomeASessionOrProviderGrant() {
        final var f = new Fixture(Map.of());
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        final var ctx = f.context("private", "shared");
        assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
        assertEquals(3, prompts.get());
        assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
        assertFalse(f.catalog(ctx).output().contains("external"));
    }

    @Test
    void deniesCancelsAndUnavailableGateFailBeforeAdmission() {
        for (final var answer : List.of(ApprovalGate.Result.DENY, ApprovalGate.Result.ALLOW_SESSION)) {
            final var f = new Fixture(Map.of());
            final var cancelled = new AtomicBoolean();
            f.permissions.setGate((title, detail, tool, args) -> {
                if (answer == ApprovalGate.Result.ALLOW_SESSION) {
                    cancelled.set(true);
                }
                return answer;
            });
            final var ctx = f.context(f.permissions, "private", "shared", cancelled::get);
            assertFalse(f.spawn(Map.of("model", "$external/shared"), ctx).success());
            assertTrue(f.started.isEmpty());
            assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
        }
        final var f = new Fixture(Map.of());
        f.permissions.setGate((title, detail, tool, args) -> {
            throw new IllegalStateException("UI disposed");
        });
        assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                .success());
        assertTrue(f.started.isEmpty());
        f.permissions.setGate((title, detail, tool, args) -> null);
        assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                .success());
        assertTrue(f.started.isEmpty());
    }

    @Test
    void defaultsAndProfilesNeverPromptButExplicitModelCanOverrideProfile() {
        final var f = new Fixture(Map.of(
                "yca.subagents.allowModelOverride",
                "true",
                "yca.subagents.includes",
                "$private/shared",
                "yca.provider.providers.1.subagents.defaultModel",
                "$external/alt"));
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        final var ctx = f.context("private", "shared");
        assertFalse(f.spawn(Map.of(), ctx).success());
        assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
        assertEquals(0, prompts.get());
        assertTrue(f.started.isEmpty());
        assertTrue(f.spawn(Map.of("agent", "reviewer", "model", "$private/shared"), ctx)
                .success());
        assertEquals("$private/shared", f.started.getFirst().admittedModel().routedReference());
        assertEquals(0, prompts.get());
    }

    @Test
    void globalOptInProviderOverrideAndIncludeInheritanceApplyToSourceProvider() {
        final var enabled = new Fixture(
                Map.of("yca.subagents.allowModelOverride", "true", "yca.subagents.includes", "$external/shared"));
        final var ctx = enabled.context("private", "shared");
        assertTrue(enabled.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertFalse(enabled.spawn(Map.of("model", "$external/alt"), ctx).success());
        assertTrue(enabled.catalog(ctx).output().contains("$external/shared"));
        assertFalse(enabled.catalog(ctx).output().contains("$external/alt"));
        final var disabled = new Fixture(Map.of(
                "yca.subagents.allowModelOverride",
                "true",
                "yca.provider.providers.1.subagents.allowModelOverride",
                "false"));
        assertFalse(disabled.spawn(Map.of("model", "$external/shared"), disabled.context("private", "shared"))
                .success());
        final var providerEnabled = new Fixture(Map.of(
                "yca.provider.providers.1.subagents.allowModelOverride",
                "true",
                "yca.provider.providers.1.subagents.includes",
                "$external/alt"));
        assertTrue(providerEnabled
                .spawn(Map.of("model", "$external/alt"), providerEnabled.context("private", "shared"))
                .success());
        assertFalse(providerEnabled
                .spawn(Map.of("model", "$external/shared"), providerEnabled.context("private", "shared"))
                .success());
        assertFalse(providerEnabled
                .catalog(providerEnabled.context("external", "shared"))
                .output()
                .contains("$private/shared"));
    }

    @Test
    void excludesAndAdministrativeCeilingsCannotBeApproved() {
        for (final var deny : List.of(
                Map.of("yca.subagents.excludes", "$external/shared"),
                Map.of("yca.provider.providers.1.subagents.excludes", "$external/shared"),
                Map.of("yca.tools.spawnSubagent.allowedProviders", "private"),
                Map.of("yca.tools.spawnSubagent.allowedModels", "$private/shared"))) {
            final var f = new Fixture(deny);
            final var prompts = new AtomicInteger();
            f.permissions.setGate((title, detail, tool, args) -> {
                prompts.incrementAndGet();
                return ApprovalGate.Result.ALLOW_SESSION;
            });
            assertFalse(f.spawn(Map.of("model", "$external/shared"), f.context("private", "shared"))
                    .success());
            assertTrue(f.started.isEmpty());
            assertEquals(0, prompts.get());
        }
    }

    @Test
    void grantsSurviveModelChangesButNotOtherTabsDisposalOrNewRuntime() {
        final var f = new Fixture(Map.of());
        f.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(f.spawn(Map.of("model", "$external/alt"), f.context("private", "shared"))
                .success());
        f.permissions.setGate(null);
        f.permissions.clearSessionGrants();
        assertTrue(f.spawn(Map.of("model", "$external/alt"), f.context("private", "unlisted"))
                .success());
        final var otherTab = new PermissionService(f.config);
        assertFalse(f.spawn(Map.of("model", "$external/alt"), f.context(otherTab, "private", "shared", () -> false))
                .success());
        assertFalse(f.catalog(f.context(otherTab, "private", "shared", () -> false))
                .output()
                .contains("$external/alt"));
        f.permissions.subagentRoutes().close();
        assertFalse(f.spawn(Map.of("model", "$external/alt"), f.context("private", "shared"))
                .success());
        assertTrue(f.permissions.subagentRoutes().routes().isEmpty());
    }

    @Test
    void changedHardPolicyStillRejectsPreviouslyApprovedRoute() {
        final var f = new Fixture(Map.of());
        f.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(f.spawn(Map.of("model", "$external/alt"), f.context("private", "shared"))
                .success());
        final var denied = new Fixture(Map.of("yca.subagents.excludes", "$external/alt"));
        final var ctx = denied.context(f.permissions, "private", "shared", () -> false);
        assertFalse(denied.spawn(Map.of("model", "$external/alt"), ctx).success());
        assertFalse(denied.catalog(ctx).output().contains("$external/alt"));
    }

    @Test
    void noSessionUnknownProviderAndStaleCatalogCannotBypassPolicy() {
        final var f = new Fixture(Map.of());
        assertFalse(f.catalog.execute(Map.of()).success());
        assertFalse(f.catalog.execute(Map.of(), ToolContext.empty()).success());
        final var ctx = f.context("private", "shared");
        assertFalse(f.spawn(Map.of("model", "$missing/shared"), ctx).success());
        assertFalse(f.spawn(Map.of("model", "$external/"), ctx).success());
        final var beforeSwitch = f.catalog(f.context("external", "shared"));
        assertTrue(beforeSwitch.output().contains("$external/shared"));
        assertFalse(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertTrue(f.started.isEmpty());
    }

    @Test
    void globalDefaultAndExplicitEmptyProviderIncludesAndDefaultAreInheritedCorrectly() {
        final var inherited = new Fixture(
                Map.of("yca.subagents.allowModelOverride", "true", "yca.subagents.defaultModel", "$external/alt"));
        assertEquals(null, inherited.manager.provider("private").subagents().allowModelOverride());
        assertTrue(inherited
                .spawn(Map.of(), inherited.context("private", "shared"))
                .success());
        assertEquals(
                "$external/alt", inherited.started.getFirst().admittedModel().routedReference());
        final var empty = new Fixture(Map.of(
                "yca.subagents.allowModelOverride",
                "true",
                "yca.subagents.includes",
                "$external/shared",
                "yca.subagents.defaultModel",
                "$external/shared",
                "yca.provider.providers.1.subagents.includes",
                "",
                "yca.provider.providers.1.subagents.defaultModel",
                ""));
        assertTrue(empty.spawn(Map.of("model", "$external/alt"), empty.context("private", "shared"))
                .success());
        assertTrue(empty.spawn(Map.of(), empty.context("private", "shared")).success());
        assertEquals("$private/shared", empty.started.getLast().admittedModel().routedReference());
    }

    @Test
    void permissiveToolModeStillRequiresDestinationConsentAndApprovedUnlistedModelsAreDiscoverable() {
        final var f = new Fixture(Map.of("yca.permissions.mode", "yolo", "yca.mode", "build"));
        final var ctx = f.context("private", "shared");
        assertFalse(f.spawn(Map.of("model", "$external/unlisted"), ctx).success());
        f.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(f.spawn(Map.of("model", "$external/unlisted"), ctx).success());
        assertTrue(f.catalog(ctx).output().contains("$external/unlisted"));
        assertFalse(f.catalog(ctx).output().contains("$external/alt"));
        f.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.DENY);
        assertFalse(f.spawn(Map.of("model", "$external/alt"), ctx).success());
    }

    @Test
    void nestedConfigurationJsonKeepsOmittedVersusExplicitEmptyIncludes() {
        final var absent = mapper.fromString(
                ProviderConfiguration.class, "{\"id\":\"private\",\"subagents\":{\"allowModelOverride\":true}}");
        assertNotNull(absent.subagents());
        assertEquals(null, absent.subagents().includes());
        final var empty = mapper.fromString(
                ProviderConfiguration.class,
                "{\"id\":\"private\",\"subagents\":{\"includes\":[],\"excludes\":[\"$external/alt\"],\"defaultModel\":\"\"}}");
        assertEquals(List.of(), empty.subagents().includes());
        assertEquals(List.of("$external/alt"), empty.subagents().excludes());
        assertEquals("", empty.subagents().defaultModel());
    }

    private final class Fixture {
        private final CodingAgentConfiguration config;
        private final ProviderManager manager;
        private final PermissionService permissions;
        private final List<SubagentRegistry.SubagentState> started = new ArrayList<>();
        private final SpawnSubagentTool spawn;
        private final ListSubagentOptionsTool catalog;

        private Fixture(final Map<String, String> overrides) {
            final var entries = new HashMap<String, String>();
            entries.put("yca.storageHome", home.toString());
            entries.put("yca.llm.maxConcurrent", "4");
            entries.put("yca.provider.providers.length", "2");
            entries.put("yca.provider.providers.0.id", "external");
            entries.put("yca.provider.providers.0.model", "shared");
            entries.put("yca.provider.providers.0.models.length", "1");
            entries.put("yca.provider.providers.0.models.0.model", "alt");
            entries.put("yca.provider.providers.1.id", "private");
            entries.put("yca.provider.providers.1.model", "shared");
            entries.put("yca.agents.length", "1");
            entries.put("yca.agents.0.name", "reviewer");
            entries.put("yca.agents.0.prompt", "Review changes.");
            entries.put("yca.agents.0.model.name", "$external/shared");
            entries.putAll(overrides);
            config = TestConfigs.codingAgent(entries);
            manager = new ProviderManager(config, mapper);
            permissions = new PermissionService(config);
            final var registry = new SubagentRegistry(config, manager);
            final var runner =
                    new SubagentRunner(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            registry,
                            new PrecisionDurationMarkerProvider(),
                            new BackoffSleeper(),
                            null,
                            null) {
                        @Override
                        public void runAsync(
                                final SubagentRegistry.SubagentState state,
                                final SessionStore sessions,
                                final SnapshotStore snapshots,
                                final TodoStore todos,
                                final ToolContext context,
                                final BooleanSupplier cancelled) {
                            started.add(state);
                            registry.complete(
                                    state.id(), SubagentRegistry.SubagentResult.success("done", "", List.of()));
                        }
                    };
            spawn = new SpawnSubagentTool(registry, runner, null, null, config, manager);
            catalog = new ListSubagentOptionsTool(new AgentProvider(config), manager, config, mapper);
        }

        private ToolContext context(final String provider, final String model) {
            return context(permissions, provider, model, () -> false);
        }

        private ToolContext context(
                final PermissionService permissions,
                final String provider,
                final String model,
                final BooleanSupplier cancelled) {
            return new ToolContext(
                    "parent",
                    null,
                    null,
                    permissions,
                    null,
                    new TodoStore() {},
                    null,
                    null,
                    null,
                    cancelled,
                    ChatSettings.EMPTY.withProviderId(provider).withModel(model),
                    null,
                    null);
        }

        private ToolResult spawn(final Map<String, Object> options, final ToolContext context) {
            final var args = new HashMap<String, Object>(options);
            args.put("task", "Review the private project");
            return spawn.execute(args, context);
        }

        private ToolResult catalog(final ToolContext context) {
            return catalog.execute(Map.of("kind", "all", "limit", 50), context);
        }
    }
}
