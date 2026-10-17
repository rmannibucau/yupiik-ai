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

import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

/**
 * The single, authoritative model choice for a child agent: explicit spawn model, then profile
 * model, then provider default, then the inherited contextual selection. The {@code routed}
 * tuple is what the policy gate and the actual execution both consume, so the two can never
 * disagree on the effective provider and model.
 */
public record EffectiveAgentModel(AgentModelRouting.Resolved routed) {
    public static EffectiveAgentModel resolve(
            final ProviderManager providers,
            final ChatSettings inherited,
            final AgentConfiguration profile,
            final String explicit,
            final String providerDefault) {
        final var base = inherited == null ? ChatSettings.EMPTY : inherited;
        final var profileModel = profile == null || profile.model() == null
                ? null
                : AgentModelRouting.refName(profile.model().name());
        final var selected = first(
                AgentModelRouting.refName(explicit),
                profileModel,
                AgentModelRouting.refName(providerDefault),
                base.model());
        if (selected == null) {
            return new EffectiveAgentModel(AgentModelRouting.Resolved.fallback(base, null));
        }
        return new EffectiveAgentModel(AgentModelRouting.route(providers, selected, base));
    }

    /** The concrete routed provider (null only when no provider manager is available). */
    public ProviderManager.ResolvedModel resolved() {
        return routed == null ? null : routed.resolved();
    }

    /** The {@link ChatSettings} carrying the effective provider id, model name and inherited tuning. */
    public ChatSettings settings() {
        return routed == null ? ChatSettings.EMPTY : routed.settings();
    }

    /** The bare effective model name (or null when nothing is configured). */
    public String reference() {
        return routed == null ? null : routed.model();
    }

    /** The {@code $provider/model} string the provider layer actually sees (or the bare name). */
    public String routedReference() {
        return routed == null ? null : routed.routedReference();
    }

    private static String first(final String... candidates) {
        for (final var candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }
}
