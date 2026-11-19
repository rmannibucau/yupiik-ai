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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
class SubagentRoutePrivacyTest {
    @TempDir
    private static Path testWorkdir;

    @TempDir
    Path home;

    private JsonMapper mapper;

    @BeforeEach
    void mapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Test
    void secureDefaultFollowsLiveSessionProviderAndCatalogIncludesItsConfiguredModels() {
        final var f = new Fixture(Map.of());
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
        assertTrue(unlisted.contains("$private/shared"), unlisted);
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
        assertFalse(f.catalog(ctx).output().contains("$external/shared"));
        assertTrue(f.catalog(ctx, "external").output().contains("$external/shared"));
        assertTrue(f.catalog(ctx, "external").output().contains("$external/alt"));
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
        assertFalse(enabled.catalog(ctx).output().contains("$external/shared"));
        assertTrue(enabled.catalog(ctx, "external").output().contains("$external/shared"));
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
        final var otherTab = new PermissionService(f.config, (f.config).resolveWorkingDir());
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
        assertFalse(f.catalog.execute(Map.of(), ToolContext.empty(home)).success());
        assertFalse(f.catalog.execute(Map.of(), ToolContext.empty(testWorkdir)).success());
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
        assertFalse(inherited
                .spawn(Map.of(), inherited.context("private", "shared"))
                .success());
        assertTrue(inherited.started.isEmpty());
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
        assertFalse(empty.spawn(Map.of("model", "$external/alt"), empty.context("private", "shared"))
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
        assertFalse(f.catalog(ctx).output().contains("$external/unlisted"));
        assertTrue(f.catalog(ctx, "external").output().contains("$external/unlisted"));
        assertFalse(f.catalog(ctx).output().contains("$external/alt"));
        f.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.DENY);
        assertFalse(f.spawn(Map.of("model", "$external/alt"), ctx).success());
    }

    @Test
    void astraParentDiscoversAndSpawnsSolOnTheSameCodexProvider() {
        final var f = new Fixture(Map.of(
                "yca.provider.providers.1.id", "codex",
                "yca.provider.providers.1.model", "gpt-6-astra",
                "yca.provider.providers.1.models.length", "1",
                "yca.provider.providers.1.models.0.model", "gpt-6.1-sol",
                "yca.provider.providers.1.subagents.defaultModel", "$codex/gpt-6.1-sol",
                "yca.provider.providers.1.subagents.excludes", "$codex/gpt-6-astra"));
        final var ctx = f.context("codex", "gpt-6-astra");
        final var filtered = f.catalog.execute(
                Map.of("provider", "codex", "kind", "models", "query", "gpt-6.1-sol", "limit", 5), ctx);
        assertModelMetadata(filtered, "$codex/gpt-6.1-sol", true, true, false);
        assertModelMetadata(f.catalog(ctx), "$codex/gpt-6.1-sol", true, true, false);
        assertFalse(f.catalog(ctx).output().contains("$codex/gpt-6-astra"));
        assertTrue(f.spawn(Map.of(), ctx).success());
        assertTrue(f.spawn(Map.of("model", "gpt-6.1-sol"), ctx).success());
        assertEquals("$codex/gpt-6.1-sol", f.started.getLast().admittedModel().routedReference());
        assertFalse(f.spawn(Map.of("model", "$codex/gpt-6-astra"), ctx).success());
    }

    @Test
    void astraSessionListsSolCatalogWithoutAuthorizingItAndPinsBareDuplicateToAstra() {
        final var f = new Fixture(Map.of(
                "yca.provider.providers.0.id", "sol",
                "yca.provider.providers.1.id", "astra",
                "yca.provider.providers.1.models.length", "1",
                "yca.provider.providers.1.models.0.model", "alt"));
        final var ctx = f.context("astra", "shared");
        final var catalog = f.catalog(ctx);
        assertTrue(catalog.success(), catalog.output());
        assertTrue(catalog.output().contains("$astra/shared"), catalog.output());
        assertTrue(catalog.output().contains("$astra/alt"), catalog.output());
        assertFalse(catalog.output().contains("$sol/"), catalog.output());
        assertModelMetadata(catalog, "$astra/shared", true, true, false);
        assertModelMetadata(catalog, "$astra/alt", false, false, true);
        assertFalse(f.spawn(Map.of("model", "$astra/alt"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "shared"), ctx).success());
        assertEquals("$astra/shared", f.started.getLast().admittedModel().routedReference());

        final var sol = f.catalog(ctx, "sol");
        assertTrue(sol.success(), sol.output());
        assertModelMetadata(sol, "$sol/shared", false, false, true);
        assertModelMetadata(sol, "$sol/alt", false, false, true);
        assertFalse(f.spawn(Map.of("model", "$sol/shared"), ctx).success());
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            assertEquals("subagent_model_route", tool);
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertTrue(f.spawn(Map.of("model", "$sol/shared"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$astra/alt"), ctx).success());
        assertEquals(2, prompts.get());
        assertFalse(f.catalog(ctx).output().contains("$sol/"));
        assertModelMetadata(f.catalog(ctx, "sol"), "$sol/shared", false, true, false);
        assertModelMetadata(f.catalog(ctx, "sol"), "$sol/alt", false, false, true);
        assertModelMetadata(f.catalog(ctx), "$astra/alt", false, true, false);
    }

    @Test
    void bareExternallyDeclaredModelNeedsConsentForSessionProviderAndItsGrantRemainsDiscoverable() {
        final var f = new Fixture(Map.of());
        final var ctx = f.context("private", "shared");
        assertFalse(f.spawn(Map.of("model", "alt"), ctx).success());
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            assertEquals("$private/alt", args.get("destination"));
            assertEquals("subagent_model_route", tool);
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertTrue(f.spawn(Map.of("model", "alt"), ctx).success());
        assertEquals("$private/alt", f.started.getLast().admittedModel().routedReference());
        assertEquals(
                "$private/alt", f.started.getLast().admittedModel().settings().model());
        assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
        assertEquals(1, prompts.get());
        assertModelMetadata(f.catalog(ctx), "$private/alt", false, true, false);
        assertFalse(f.catalog(ctx).output().contains("$external/"));
    }

    @Test
    void configuredSameProviderDefaultIsAuthorizedWithOverridesDisabledButDifferentParentIsNot() {
        final var f = new Fixture(Map.of(
                "yca.provider.providers.1.models.length", "1",
                "yca.provider.providers.1.models.0.model", "alt",
                "yca.provider.providers.1.subagents.defaultModel", "$private/alt",
                "yca.provider.providers.1.subagents.allowModelOverride", "false"));
        final var ctx = f.context("private", "shared");
        assertModelMetadata(f.catalog(ctx), "$private/alt", true, true, false);
        assertModelMetadata(f.catalog(ctx), "$private/shared", false, false, true);
        assertTrue(f.spawn(Map.of(), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
        assertFalse(f.spawn(Map.of("model", "$private/shared"), ctx).success());
        assertEquals(2, f.started.size());
        f.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(f.spawn(Map.of("model", "$private/shared"), ctx).success());
    }

    @ParameterizedTest
    @ValueSource(strings = {"omitted", ""})
    void emptyOrAbsentIncludesDoesNotAuthorizeAlternativesEvenInYolo(final String includes) {
        final var overrides = new HashMap<String, String>();
        overrides.put("yca.subagents.allowModelOverride", "true");
        overrides.put("yca.permissions.mode", "yolo");
        overrides.put("yca.mode", "build");
        overrides.put("yca.provider.providers.1.models.length", "1");
        overrides.put("yca.provider.providers.1.models.0.model", "alt");
        if (!"omitted".equals(includes)) {
            overrides.put("yca.subagents.includes", includes);
        }
        final var f = new Fixture(overrides);
        final var ctx = f.context("private", "shared");
        assertModelMetadata(f.catalog(ctx), "$private/alt", false, false, true);
        assertFalse(f.spawn(Map.of("model", "$private/alt"), ctx).success());
        assertFalse(f.spawn(Map.of("model", "$external/alt"), ctx).success());
        assertTrue(f.started.isEmpty());
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            assertEquals("subagent_model_route", tool);
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
        assertEquals(2, prompts.get());
        assertModelMetadata(f.catalog(ctx), "$private/alt", false, true, false);
        assertFalse(f.catalog(ctx).output().contains("$external/"));
    }

    @Test
    void exactIncludesAuthorizeExplicitAlternativesWithoutPromptsButNotImplicitCrossProviderRoutes() {
        final var f = new Fixture(Map.of(
                "yca.permissions.mode", "yolo",
                "yca.mode", "build",
                "yca.subagents.allowModelOverride", "true",
                "yca.subagents.includes", "$private/alt,$external/shared",
                "yca.subagents.defaultModel", "$external/shared"));
        final var ctx = f.context("private", "shared");
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertFalse(f.spawn(Map.of(), ctx).success());
        assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$private/alt"), ctx).success());
        assertTrue(f.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertEquals(0, prompts.get());
        // An existing session grant must not turn an implicit cross-provider route into consent.
        assertTrue(f.spawn(Map.of("model", "$external/alt"), ctx).success());
        assertEquals(1, prompts.get());
        final var changedDefault = new Fixture(Map.of("yca.subagents.defaultModel", "$external/alt"));
        final var granted = changedDefault.context(f.permissions, "private", "shared", () -> false);
        assertFalse(changedDefault.spawn(Map.of(), granted).success());
        assertEquals(1, prompts.get());
        assertTrue(changedDefault.started.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "yca.subagents.excludes",
                "yca.provider.providers.1.subagents.excludes",
                "yca.provider.providers.0.subagents.excludes"
            })
    void globalSourceAndDestinationExcludesAreAdditiveAndBeatIncludesAndPreviousGrants(final String exclude) {
        final var approved = new Fixture(Map.of());
        approved.permissions.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(approved.spawn(Map.of("model", "$external/shared"), approved.context("private", "shared"))
                .success());
        final var overrides = new HashMap<String, String>();
        overrides.put("yca.subagents.allowModelOverride", "true");
        overrides.put("yca.subagents.includes", "$external/shared");
        // Non-matching narrower lists must not replace a broader exclusion.
        overrides.put("yca.subagents.excludes", "$private/irrelevant");
        overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
        overrides.put("yca.provider.providers.0.subagents.excludes", "$external/irrelevant");
        overrides.put(exclude, "$external/shared");
        final var denied = new Fixture(overrides);
        final var ctx = denied.context(approved.permissions, "private", "shared", () -> false);
        final var prompts = new AtomicInteger();
        approved.permissions.setGate((title, detail, tool, args) -> {
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertFalse(denied.spawn(Map.of("model", "$external/shared"), ctx).success());
        assertFalse(denied.catalog(ctx, "external").output().contains("$external/shared"));
        assertTrue(denied.started.isEmpty());
        assertEquals(0, prompts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"yca.subagents.excludes", "yca.provider.providers.1.subagents.excludes"})
    void sameProviderExcludesBeatParentDefaultProfileAndIncludes(final String exclude) {
        final var overrides = new HashMap<String, String>();
        overrides.put("yca.subagents.allowModelOverride", "true");
        overrides.put("yca.subagents.includes", "$private/shared");
        overrides.put("yca.subagents.defaultModel", "$private/shared");
        overrides.put("yca.agents.0.model.name", "$private/shared");
        overrides.put("yca.subagents.excludes", "$private/irrelevant");
        overrides.put("yca.provider.providers.1.subagents.excludes", "$private/irrelevant");
        overrides.put(exclude, "$private/shared");
        final var f = new Fixture(overrides);
        final var ctx = f.context("private", "shared");
        final var prompts = new AtomicInteger();
        f.permissions.setGate((title, detail, tool, args) -> {
            prompts.incrementAndGet();
            return ApprovalGate.Result.ALLOW_SESSION;
        });
        assertFalse(f.spawn(Map.of(), ctx).success());
        assertFalse(f.spawn(Map.of("agent", "reviewer"), ctx).success());
        assertFalse(f.spawn(Map.of("model", "$private/shared"), ctx).success());
        assertFalse(f.catalog(ctx).output().contains("$private/shared"));
        assertTrue(f.started.isEmpty());
        assertEquals(0, prompts.get());
    }

    @SuppressWarnings("unchecked")
    private void assertModelMetadata(
            final ToolResult catalog,
            final String reference,
            final boolean defaultModel,
            final boolean authorized,
            final boolean requiresApproval) {
        assertTrue(catalog.success(), catalog.output());
        final var result = mapper.fromString(Map.class, catalog.output());
        final var options = (List<Map<String, Object>>) result.get("options");
        final var model = options.stream()
                .filter(option -> reference.equals(option.get("reference")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + reference + " in " + catalog.output()));
        assertEquals(defaultModel, model.get("default"), catalog.output());
        assertEquals(authorized, model.get("authorized"), catalog.output());
        assertEquals(requiresApproval, model.get("requires_approval"), catalog.output());
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
            permissions = new PermissionService(config, (config).resolveWorkingDir());
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
                    testWorkdir);
        }

        private ToolResult spawn(final Map<String, Object> options, final ToolContext context) {
            final var args = new HashMap<String, Object>(options);
            args.put("task", "Review the private project");
            return spawn.execute(args, context);
        }

        private ToolResult catalog(final ToolContext context) {
            return catalog.execute(Map.of("kind", "all", "limit", 50), context);
        }

        private ToolResult catalog(final ToolContext context, final String provider) {
            return catalog.execute(Map.of("kind", "models", "limit", 50, "provider", provider), context);
        }
    }
}
