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

import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.SpawnSubagentToolConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class EffectiveAgentModelTest {
    @Test
    void resolvesExplicitThenProfileThenProviderDefaultThenInherited() {
        final var profile = new AgentConfiguration(
                "reviewer",
                "Review",
                null,
                new ModelVariantConfiguration("profile-model", null, null, null, null, null, null),
                null);
        final var inherited = ChatSettings.EMPTY.withProviderId("parent").withModel("inherited");

        assertEquals(
                "explicit",
                EffectiveAgentModel.resolve(null, inherited, profile, " explicit ", "provider-default")
                        .reference());
        assertEquals(
                "profile-model",
                EffectiveAgentModel.resolve(null, inherited, profile, null, "provider-default")
                        .reference());
        assertEquals(
                "provider-default",
                EffectiveAgentModel.resolve(null, inherited, null, null, "provider-default")
                        .reference());
        assertEquals(
                "inherited",
                EffectiveAgentModel.resolve(null, inherited, null, null, null).reference());
    }

    @Test
    void anUnsetProviderDefaultFallsThroughToTheInheritedModel() {
        final var inherited = ChatSettings.EMPTY.withProviderId("parent").withModel("inherited");
        // "default" is the implicit model-variant placeholder, not a real model: it must not win.
        assertEquals(
                "inherited",
                EffectiveAgentModel.resolve(null, inherited, null, null, "default")
                        .reference());
    }

    @Test
    void policyRoutesBareModelToItsProviderBeforeAllowing() {
        final var policy = new SpawnSubagentToolConfiguration(null, List.of("child"), List.of("$child/model"));
        // resolve with a provider that declares "model" so the routed reference is $child/model.
        final var effective = routeEqually(policy);
        assertTrue(SubagentModelPolicy.allows(policy, effective));
    }

    @Test
    void policyRejectsModelOutsideTheAllowlist() {
        final var policy = new SpawnSubagentToolConfiguration(null, List.of("child"), List.of("$child/other"));
        final var effective = routeEqually(policy);
        assertFalse(SubagentModelPolicy.allows(policy, effective));
    }

    /** Builds an EffectiveAgentModel whose effective model is "model" and provider id "child" (mirrors the routed shape). */
    private static EffectiveAgentModel routeEqually(final SpawnSubagentToolConfiguration ignored) {
        return new EffectiveAgentModel(new AgentModelRouting.Resolved(
                null, ChatSettings.EMPTY.withProviderId("child").withModel("model"), "model", "$child/model"));
    }
}
