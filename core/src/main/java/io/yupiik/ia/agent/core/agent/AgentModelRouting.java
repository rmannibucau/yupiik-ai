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

import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

/**
 * The single routing primitive for subagent/profile model references. Every downstream consumer
 * (spawn gate, {@code SubagentRunner}, {@code AgentLoop}, the options catalog) resolves the same
 * authoritative {@code Resolved} tuple so preflight policy and actual wire selection can never
 * diverge.
 */
final class AgentModelRouting {
    private AgentModelRouting() {}

    /**
     * The configured model name of a {@link io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration},
     * or {@code null} when it is unset/blank or the implicit {@code "default"} placeholder. Centralizes
     * the "is this really configured?" interpretation so no caller re-inlines the {@code "default"} literal.
     */
    static String refName(final String name) {
        if (name == null) {
            return null;
        }
        final var value = name.strip();
        return value.isBlank() || "default".equalsIgnoreCase(value) ? null : value;
    }

    /**
     * Authority for a run: the concrete provider + model that will actually be sent, the
     * {@link ChatSettings} carrying them, the bare effective model name, and the routed
     * {@code $provider/model} string the provider layer sees.
     */
    record Resolved(
            ProviderManager.ResolvedModel resolved, ChatSettings settings, String model, String routedReference) {

        static Resolved fallback(final ChatSettings settings, final String reference) {
            return new Resolved(null, settings, refName(reference), refName(reference));
        }
    }

    static Resolved route(final ProviderManager pm, final String reference, final ChatSettings base) {
        if (reference == null) {
            return null;
        }
        final var ref = reference.strip();
        if (ref.isBlank()) {
            return null;
        }
        var target = base == null ? ChatSettings.EMPTY : base;
        if (pm == null) {
            // no provider manager (tests / minimal runtime): keep the bare reference on the base settings
            return new Resolved(null, target.withModel(ref), ref, ref);
        }
        final var resolved = pm.resolve(ref);
        final var provider = resolved == null ? null : resolved.provider();
        final var providerId =
                provider == null || provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
        final var model =
                resolved == null || resolved.model() == null || resolved.model().isBlank()
                        ? (provider == null || provider.model() == null ? ref : provider.model())
                        : resolved.model();
        target = ChatSettings.EMPTY.withProviderId(providerId).withModel(model).inheritFrom(target);
        final var routed = "$".concat(providerId).concat("/").concat(model);
        return new Resolved(resolved, target, model, routed);
    }
}
