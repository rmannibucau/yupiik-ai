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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelTuningConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderModelConfiguration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of the configured OpenAI compatible providers and their model references.
 *
 * <p>A model <em>reference</em> is a string that may be prefixed with a provider id as
 * {@code $<id>/<model>} (eg. {@code $local/gpt-4o-mini}). When the reference has no {@code $}
 * prefix it is resolved against the current (default) provider. Everywhere the configuration
 * accepts a model name ("default chat model", compaction/pruning model, enrichment model, ...)
 * it can use the {@code $provider/model} form.</p>
 */
@ApplicationScoped
public class ProviderManager {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;

    private volatile String currentProviderId;
    private final ConcurrentHashMap<ProviderConfiguration, Map<String, Object>> optionsCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ModelTuningConfiguration, Map<String, Object>> tuningOptionsCache =
            new ConcurrentHashMap<>();

    public ProviderManager(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    protected ProviderManager() {
        this.configuration = null;
        this.mapper = null;
    }

    /** A provider matched with a concrete model name resolved from a model reference. */
    public record ResolvedModel(ProviderConfiguration provider, String model) {}

    /** The resolved list of providers; the legacy flat scalars are synthesized as a "default" provider if none are listed. */
    public List<ProviderConfiguration> providers() {
        final var configured = configuration.provider().providers();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        return List.of(configuration.provider().asSingleProvider());
    }

    /** The current provider: the runtime-selected one, else the default one (defaultProvider or the first). */
    public ProviderConfiguration current() {
        final var id = currentProviderId;
        if (id != null && !id.isBlank()) {
            final var byId = provider(id);
            if (byId != null) {
                return byId;
            }
        }
        return defaultProvider();
    }

    public void setProvider(final String id) {
        final var found = provider(id);
        if (found != null) {
            this.currentProviderId = id;
        }
    }

    public String currentProviderId() {
        final var current = current();
        final var id = current == null ? null : current.id();
        return id == null || id.isBlank() ? "default" : id;
    }

    public ProviderConfiguration defaultProvider() {
        final var resolved = configuration.provider().resolvedDefaultProvider();
        final var p = provider(resolved);
        return p == null ? (providers().isEmpty() ? null : providers().get(0)) : p;
    }

    /** Position of a provider for styling: 0 for the implicit flat-scalar default provider, {@code 1 + index} within the configured {@code providers} list. */
    public int providerPosition(final ProviderConfiguration provider) {
        final var configured = configuration.provider().providers();
        if (configured == null || configured.isEmpty()) {
            return 0;
        }
        final int idx = provider == null ? -1 : configured.indexOf(provider);
        return idx < 0 ? 0 : 1 + idx;
    }

    /** Look up a provider by id; falls back to the default provider when not found. */
    public ProviderConfiguration provider(final String id) {
        if (id == null || id.isBlank()) {
            return defaultProvider();
        }
        for (final var p : providers()) {
            if (id.equals(p.id()) || "default".equals(id) && (p.id() == null || p.id().isBlank())) {
                return p;
            }
        }
        return defaultProvider();
    }

    /** Resolve the current provider default model reference to its concrete model name. */
    public ResolvedModel resolveDefault() {
        final var current = current();
        return new ResolvedModel(current, current == null || current.model() == null ? "" : current.model());
    }

    /**
     * Resolve a model reference to a (provider, model) pair. A {@code $<id>/<model>} reference selects
     * the provider by id; a plain model name resolves against the current provider.
     */
    public ResolvedModel resolve(final String modelReference) {
        final var ref = modelReference;
        if (ref != null && ref.startsWith("$")) {
            final var slash = ref.indexOf('/');
            final var id = slash > 1 ? ref.substring(1, slash) : ref.substring(1);
            final var name = slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : "";
            return new ResolvedModel(provider(id), name);
        }
        final var current = current();
        return new ResolvedModel(current, ref == null ? "" : ref);
    }

    /** Resolve a model reference to its concrete model name (blank references fall back to the current provider model). */
    public String resolveModel(final String modelReference) {
        if (modelReference == null || modelReference.isBlank()) {
            final var current = current();
            return current == null || current.model() == null ? "" : current.model();
        }
        final var resolved = resolve(modelReference);
        return resolved.model() == null ? "" : resolved.model();
    }

    /**
     * Parse the provider-specific options (JSON object) into an unmodifiable map, or an empty map when
     * {@code providerSpecificOptions} is blank or does not start with '{'. Parsed once per provider
     * configuration and cached: the options ride on every request, re-deserializing them each call is waste.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> providerOptions(final ProviderConfiguration provider) {
        if (provider == null
                || provider.providerSpecificOptions() == null
                || !provider.providerSpecificOptions().startsWith("{")) {
            return Collections.emptyMap();
        }
        return optionsCache.computeIfAbsent(provider, p -> {
            try {
                final var parsed = mapper.fromString(Object.class, p.providerSpecificOptions());
                if (!(parsed instanceof Map)) {
                    return Collections.emptyMap();
                }
                return Collections.unmodifiableMap(new HashMap<>((Map<String, Object>) parsed));
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException("Invalid providerSpecificOptions JSON: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Resolve the effective output token budget override for a model of a provider: the {@code behavior.outputTokens}
     * of the first matching model entry - exact id first, then first pattern contained in the model name - else the
     * provider-level {@code tuning.outputTokens}, else 0 when no override applies. The provider {@code outputTokens}
     * default is not folded in here so the caller can distinguish "override" from "default".
     */
    public int outputTokensOverride(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().outputTokens() != null) {
            final var tokens = entry.behavior().outputTokens();
            if (tokens > 0) {
                return tokens;
            }
        }
        final var tuning = providerTuning(provider);
        return tuning != null && tuning.outputTokens() != null && tuning.outputTokens() > 0 ? tuning.outputTokens() : 0;
    }

    /**
     * Resolve the effective system prompt max character budget override for a model of a provider: the
     * {@code behavior.systemPromptMaxChars} of the first matching model entry - exact id first, then first pattern
     * contained in the model name - else the provider-level {@code tuning.systemPromptMaxChars}, else 0 when no override
     * applies. The global llm default and the window-derived fallback are not folded in here so the caller can distinguish
     * "override" from "default".
     */
    public int systemPromptMaxChars(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().systemPromptMaxChars() != null) {
            final var chars = entry.behavior().systemPromptMaxChars();
            if (chars > 0) {
                return chars;
            }
        }
        final var tuning = providerTuning(provider);
        return tuning != null && tuning.systemPromptMaxChars() != null && tuning.systemPromptMaxChars() > 0
                ? tuning.systemPromptMaxChars()
                : 0;
    }

    /**
     * Resolve the per-model reasoning effort default: {@code behavior.reasoningEffort} of the first matching entry, else the
     * provider-level {@code tuning.reasoningEffort}, else null.
     */
    public String reasoningEffort(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        final var perModel = entry == null || entry.behavior() == null
                ? null
                : entry.behavior().reasoningEffort();
        if (perModel != null && !perModel.isBlank()) {
            return perModel;
        }
        final var tuning = providerTuning(provider);
        return tuning == null
                        || tuning.reasoningEffort() == null
                        || tuning.reasoningEffort().isBlank()
                ? null
                : tuning.reasoningEffort();
    }

    /**
     * Resolve the effective tuning defaults for a model of a provider: the {@code behavior} of the first matching model entry,
     * else the provider-level {@code tuning}. Returns null only when neither is configured.
     */
    public ModelTuningConfiguration tuning(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null) {
            return entry.behavior();
        }
        return providerTuning(provider);
    }

    /** The provider-level tuning record, or null. */
    private ModelTuningConfiguration providerTuning(final ProviderConfiguration provider) {
        return provider == null ? null : provider.tuning();
    }

    /**
     * Resolve the named runtime variants for a model of a provider, with fallback: the per-model {@code behavior.variants},
     * else the provider-level variants, else an empty list (implicit per-family discovery applies). Unmodifiable.
     */
    public List<ModelVariantConfiguration> variants(final ProviderConfiguration provider, final String model) {
        final var tuning = tuning(provider, model);
        if (tuning != null && tuning.variants() != null && !tuning.variants().isEmpty()) {
            return List.copyOf(tuning.variants());
        }
        return List.of();
    }

    /**
     * Effective raw provider specific options JSON string for a model of a provider: the per-model
     * {@code behavior.providerSpecificOptions}, else the provider-level {@code providerSpecificOptions}. Null when neither.
     */
    public String providerSpecificOptions(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        final var perModel =
                entry == null || entry.behavior() == null || entry.behavior().providerSpecificOptions() == null
                        ? null
                        : entry.behavior().providerSpecificOptions();
        if (perModel != null && !perModel.isBlank()) {
            return perModel;
        }
        if (provider != null
                && provider.providerSpecificOptions() != null
                && !provider.providerSpecificOptions().isBlank()) {
            return provider.providerSpecificOptions();
        }
        return null;
    }

    /**
     * Parse a model-level {@code providerSpecificOptions} JSON object into an unmodifiable map, or an empty map when it is
     * blank or does not start with '{'. Cached per tuning record.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> tuningOptions(final ModelTuningConfiguration tuning) {
        if (tuning == null
                || tuning.providerSpecificOptions() == null
                || !tuning.providerSpecificOptions().startsWith("{")) {
            return Collections.emptyMap();
        }
        return tuningOptionsCache.computeIfAbsent(tuning, t -> {
            try {
                final var parsed = mapper.fromString(Object.class, t.providerSpecificOptions());
                if (!(parsed instanceof Map)) {
                    return Collections.emptyMap();
                }
                return Collections.unmodifiableMap(new HashMap<>((Map<String, Object>) parsed));
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException(
                        "Invalid providerSpecificOptions JSON for a model tuning: " + e.getMessage(), e);
            }
        });
    }

    /** Index of the first matching model entry within the provider {@code models} list, or -1 when the model is unlisted. */
    public int modelIndex(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry == null || provider == null || provider.models() == null) {
            return -1;
        }
        return provider.models().indexOf(entry);
    }

    /** The first matching model entry: an exact id wins over the first contains match, else null. */
    private ProviderModelConfiguration matchModel(final ProviderConfiguration provider, final String model) {
        if (provider == null || provider.models() == null || model == null || model.isBlank()) {
            return null;
        }
        for (final var entry : provider.models()) {
            if (model.equals(entry.model())) {
                return entry;
            }
        }
        for (final var entry : provider.models()) {
            if (contains(entry.model(), model)) {
                return entry;
            }
        }
        return null;
    }

    /** True when {@code model} contains the {@code pattern} (asterisks are ignored) and the pattern is not blank. */
    private static boolean contains(final String pattern, final String model) {
        final var needle = pattern == null ? "" : pattern.replace("*", "");
        return !needle.isEmpty() && model.contains(needle);
    }
}
