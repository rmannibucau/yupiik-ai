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
import io.yupiik.ia.agent.core.configuration.InBandToolCallsMode;
import io.yupiik.ia.agent.core.configuration.ModelConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelTuningConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderModelConfiguration;
import io.yupiik.ia.agent.core.configuration.ReasoningFormatMode;
import io.yupiik.ia.agent.core.configuration.RepairToolCallArgumentsMode;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
     * The effective default subagent model for a context, using the identical fallback in both
     * {@code spawn_subagent} and the options catalog: the effective provider's
     * {@code defaultSubagentModel}, then the contextual selection's model, then the provider default
     * model. Centralizing it keeps the discovery view aligned with what a spawn really inherits.
     */
    public String effectiveSubagentDefault(final ChatSettings settings) {
        final var ctx = settings == null ? ChatSettings.EMPTY : settings;
        final var provider =
                ctx.providerId() == null || ctx.providerId().isBlank() ? current() : provider(ctx.providerId());
        if (provider != null
                && provider.defaultSubagentModel() != null
                && !provider.defaultSubagentModel().isBlank()) {
            return provider.defaultSubagentModel().strip();
        }
        if (ctx.model() != null && !ctx.model().isBlank()) {
            return ctx.model().strip();
        }
        return provider == null || provider.model() == null
                ? null
                : provider.model().strip();
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
            final var target = provider(id);
            final var name = slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : "";
            // $id/ (no model part): the provider's own configured model applies
            return new ResolvedModel(target, name.isBlank() ? (target == null ? "" : target.model()) : name);
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
     * Turn a configured background-model reference (compact/memory/enrichment) into a routable
     * one: a {@code $provider/model} reference is kept as-is, a bare name is pinned to the
     * provider declaring that model (when identifiable) so a session running on another provider
     * cannot hijack the call into a "No such model" 400, and a blank reference stays null.
     */
    public String toRoutedReference(final String modelReference) {
        if (modelReference == null || modelReference.isBlank()) {
            return null;
        }
        final var ref = modelReference.strip();
        if (ref.startsWith("$")) {
            return ref;
        }
        for (final var p : providers()) {
            if (p.models() != null && p.models().stream().anyMatch(m -> m != null && ref.equals(m.model()))) {
                return "$" + (p.id() == null || p.id().isBlank() ? "default" : p.id()) + "/" + ref;
            }
        }
        return ref;
    }

    /**
     * The routable compact model reference for a session: the effective provider's
     * {@code backgroundModelOverrides.compact} (blank = this provider's own default model,
     * {@code inherit} = the global {@code yca.model.compact} chain, otherwise that model) wins,
     * then the global {@code yca.model.compact}, then null (no explicit model: the session's
     * selection routes the call). Returns a reference the ChatClient routes correctly: bare
     * provider-level names are pinned to their provider.
     */
    public String resolvedCompact(final ChatSettings settings) {
        return resolveBackground(
                settings,
                configuration.model() == null
                        ? null
                        : modelName(configuration.model().compact()),
                ModelConfiguration::compact);
    }

    /**
     * The routable prune model reference for a session, with the global {@code resolvedPrune}
     * fallback chain (prune falls back to compact); otherwise like {@link #resolvedCompact}.
     */
    public String resolvedPrune(final ChatSettings settings) {
        return resolveBackground(
                settings,
                configuration.model() == null ? null : configuration.model().resolvedPrune(),
                ModelConfiguration::prune);
    }

    /**
     * The routable memory model reference for a session, with the global {@code resolvedMemory}
     * fallback chain (memory falls back to compact); otherwise like {@link #resolvedCompact}.
     */
    public String resolvedMemory(final ChatSettings settings) {
        return resolveBackground(
                settings,
                configuration.model() == null ? null : configuration.model().resolvedMemory(),
                ModelConfiguration::memory);
    }

    /**
     * True when the memory background model resolves to the same effective model id as the
     * session's main model. When the models are identical, extraction is as expensive as a
     * main-turn call, so the AUTO memory mode picks the LAZY (rare) extraction regime; a
     * distinct (usually cheaper) memory model makes eager FULL extraction the default. If
     * either side resolves to the provider default/blank, it is treated as the same model
     * (conservative -> LAZY). Uses only the {@link #resolveModel} / {@link #resolvedMemory}
     * resolution helpers.
     */
    public boolean memoryUsesMainModel(final ChatSettings settings) {
        final var effective = settings == null ? ChatSettings.EMPTY : settings;
        final var provider = provider(effective.providerId());
        final var mainRef = effective.model() != null
                ? effective.model()
                : (provider == null || provider.model() == null ? null : provider.model());
        final var main = mainRef == null ? null : resolveModel(mainRef);
        if (main == null || main.isBlank()) {
            return true; // no explicit main model -> default; treat as same to be safe
        }
        final var mem = resolvedMemory(effective);
        if (mem == null || mem.isBlank()) {
            return true; // memory falls back to the default model -> same
        }
        final var resolvedMem = resolve(mem);
        return main.equals(resolvedMem == null ? mem : resolvedMem.model());
    }

    /**
     * The tuning payload (temperature / maxTokens / reasoningEffort / topP / topK / extra) resolved
     * for a background path, following the same provider -> inherit -> global chain as the model
     * name. Returns the effective {@link ModelVariantConfiguration} (may be null when nothing is
     * configured for the path and it falls back to the provider default).
     */
    public ModelVariantConfiguration resolvedBackgroundTuning(final ChatSettings settings, final String path) {
        final var override = settings == null ? null : settings.providerId();
        final var provider = provider(override == null || override.isBlank() ? currentProviderId() : override);
        final var overrides = provider == null ? null : provider.backgroundModelOverrides();
        if (overrides != null
                && (hasModelName(overrides.compact())
                        || hasModelName(overrides.prune())
                        || hasModelName(overrides.memory()))) {
            // the effective provider configured a background model group: its path tuning wins
            final var t = tuningFor(overrides, path);
            if (t != null) {
                return t;
            }
        }
        // fall back to the global yca.model.* chain (memory/prune fall back to compact)
        return configuration.model() == null ? null : tuningFor(configuration.model(), path);
    }

    private static ModelVariantConfiguration tuningFor(final ModelConfiguration mc, final String path) {
        return switch (path) {
            case "memory" -> mc.memoryTuning();
            case "prune" -> mc.pruneTuning();
            default -> mc.compactTuning();
        };
    }

    private static String modelName(final ModelVariantConfiguration v) {
        if (v == null || v.name() == null) {
            return null;
        }
        final var name = v.name().strip();
        // "default" is the implicit ModelVariantConfiguration sentinel: on the background paths the
        // value is the MODEL name, so an unset field carries "default" and must fall back, not route
        // to a literal $provider/default model.
        return name.isBlank() || "default".equalsIgnoreCase(name) ? null : name;
    }

    private static boolean hasModelName(final ModelVariantConfiguration v) {
        return modelName(v) != null;
    }

    /**
     * The background-model resolution core behind {@link #resolvedCompact}, {@link #resolvedPrune}
     * and {@link #resolvedMemory}: the effective provider's (session override, else process
     * default) {@code backgroundModelOverrides} entry decides per the blank/inherit/value
     * semantics on the requested field; inherited and global references are made routable via
     * {@link #toRoutedReference} so a bare global name is still pinned to the provider hosting it
     * when identifiable.
     */
    private String resolveBackground(
            final ChatSettings settings,
            final String global,
            final Function<ModelConfiguration, ModelVariantConfiguration> field) {
        final var override = settings == null ? null : settings.providerId();
        final var provider = provider(override == null || override.isBlank() ? currentProviderId() : override);
        final var overrides = provider == null ? null : provider.backgroundModelOverrides();
        final var groupPresent = overrides != null
                && (hasModelName(overrides.compact())
                        || hasModelName(overrides.prune())
                        || hasModelName(overrides.memory()));
        if (groupPresent) {
            // the group is present (at least one field configured): the requested field decides
            final var raw = modelName(field.apply(overrides));
            if (raw != null && !raw.isBlank()) {
                if (ProviderConfiguration.isInheritKeyword(raw)) {
                    return toRoutedReference(global);
                }
                // a bare name on a provider is that provider's own model; a $ref routes explicitly
                return raw.startsWith("$") ? raw.strip() : "$" + providerIdOf(provider) + "/" + raw.strip();
            }
            // the field is blank: the provider opts out of a dedicated background model,
            // its own default model applies
            return null;
        }
        return toRoutedReference(global);
    }

    /** The provider id used for routing ({@code "default"} when blank): see {@link #providerIdOf} in ChatClient. */
    private String providerIdOf(final ProviderConfiguration provider) {
        final var id = provider == null || provider.id() == null ? "" : provider.id();
        return id.isBlank() ? "default" : id;
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
     * Resolve the effective context window override for a model of a provider: the {@code contextWindow} of the
     * first matching model entry - exact id first, then first pattern contained in the model name - else the
     * provider-level {@code contextWindow}, else 0 when no override applies. The 0-means-unset convention lets the
     * caller continue the chain (per-model > provider > /v1/models detection > baseline).
     */
    public int contextWindowOverride(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.contextWindow() > 0) {
            return entry.contextWindow();
        }
        return provider == null || provider.contextWindow() <= 0 ? 0 : provider.contextWindow();
    }

    /**
     * Resolve the effective tool call arguments repair mode for a model of a provider: the
     * {@code behavior.repairToolCallArguments} of the first matching model entry when it is not AUTO,
     * else the provider-level {@code tuning.repairToolCallArguments} when not AUTO, else the AUTO
     * heuristic on the effective model name (enabled for any Qwen 27B variant).
     */
    public boolean repairToolCallArguments(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        final var perModel = entry == null || entry.behavior() == null
                ? null
                : entry.behavior().repairToolCallArguments();
        if (perModel != null && perModel != RepairToolCallArgumentsMode.AUTO) {
            return perModel == RepairToolCallArgumentsMode.ENABLED;
        }
        final var tuning = providerTuning(provider);
        final var providerMode = tuning == null ? null : tuning.repairToolCallArguments();
        if (providerMode != null && providerMode != RepairToolCallArgumentsMode.AUTO) {
            return providerMode == RepairToolCallArgumentsMode.ENABLED;
        }
        final var auto =
                perModel != null ? perModel : providerMode != null ? providerMode : RepairToolCallArgumentsMode.AUTO;
        return auto.isAutoCandidate(model);
    }

    /**
     * Resolve whether in-band tool-call markup should be extracted for a model of a provider: the
     * {@code behavior.inBandToolCalls} of the first matching model entry when not AUTO, else the
     * provider-level {@code tuning.inBandToolCalls} when not AUTO, else the AUTO default (extract
     * only when the provider produced no native tool call).
     */
    public boolean inBandToolCalls(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        final var perModel = entry == null || entry.behavior() == null
                ? null
                : entry.behavior().inBandToolCalls();
        if (perModel != null && perModel != InBandToolCallsMode.AUTO) {
            return perModel == InBandToolCallsMode.ENABLED;
        }
        final var tuning = providerTuning(provider);
        final var providerMode = tuning == null ? null : tuning.inBandToolCalls();
        if (providerMode != null && providerMode != InBandToolCallsMode.AUTO) {
            return providerMode == InBandToolCallsMode.ENABLED;
        }
        final var auto = perModel != null ? perModel : providerMode != null ? providerMode : InBandToolCallsMode.AUTO;
        return auto == InBandToolCallsMode.AUTO;
    }

    /**
     * Resolve the reasoning wire format for a model of a provider: the {@code behavior.reasoningFormat}
     * of the first matching model entry when not AUTO, else the provider-level {@code tuning.reasoningFormat}
     * when not AUTO, else the AUTO default (GLM/Qwen whole-line think handling).
     */
    public ReasoningFormatMode reasoningFormat(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        final var perModel = entry == null || entry.behavior() == null
                ? null
                : entry.behavior().reasoningFormat();
        if (perModel != null && perModel != ReasoningFormatMode.AUTO) {
            return perModel;
        }
        final var tuning = providerTuning(provider);
        final var providerMode = tuning == null ? null : tuning.reasoningFormat();
        if (providerMode != null && providerMode != ReasoningFormatMode.AUTO) {
            return providerMode;
        }
        return ReasoningFormatMode.AUTO;
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
     * Resolve the effective exploration-plateau round limit for a model of a provider: the
     * {@code behavior.plateauRounds} of the first matching model entry when non-zero (a negative value
     * is a nudge-only flag), else the provider-level {@code tuning.plateauRounds} when non-zero, else 0
     * so the caller applies the global {@code llm.plateauRounds} default (0 disables the guard
     * entirely, a negative resolved value disables the hard-stop and keeps only the nudge).
     */
    public int plateauRounds(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().plateauRounds() != null) {
            final var rounds = entry.behavior().plateauRounds();
            if (rounds != 0) {
                return rounds;
            }
        }
        final var tuning = providerTuning(provider);
        if (tuning != null && tuning.plateauRounds() != null && tuning.plateauRounds() != 0) {
            return tuning.plateauRounds();
        }
        return 0;
    }

    /**
     * Resolve the effective doom-loop success threshold for a model of a provider: the
     * {@code behavior.repeatSuccessThreshold} of the first matching model entry when > 0, else the
     * provider-level {@code tuning.repeatSuccessThreshold} when > 0, else 0 so the caller applies the
     * global {@code yca.tools.repeatSuccessThreshold} default (0 at that level disables the guard).
     */
    public int repeatSuccessThreshold(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().repeatSuccessThreshold() != null) {
            final var threshold = entry.behavior().repeatSuccessThreshold();
            if (threshold > 0) {
                return threshold;
            }
        }
        final var tuning = providerTuning(provider);
        return tuning != null && tuning.repeatSuccessThreshold() != null && tuning.repeatSuccessThreshold() > 0
                ? tuning.repeatSuccessThreshold()
                : 0;
    }

    /**
     * Resolve the effective plateau feedback strategy for a model of a provider: the
     * {@code behavior.plateauFeedback} of the first matching model entry, else the provider-level
     * {@code tuning.plateauFeedback}, else null so the caller applies the global
     * {@code llm.plateauFeedback} default. The strategy controls whether the model is nudged once
     * at the plateau limit-1 / limit-2 before the hard stop.
     */
    public PlateauFeedbackMode plateauFeedback(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().plateauFeedback() != null) {
            return entry.behavior().plateauFeedback();
        }
        final var tuning = providerTuning(provider);
        return tuning == null ? null : tuning.plateauFeedback();
    }

    /**
     * Resolve whether a non-truncated text-only reply with unfinished todos continues the turn
     * automatically (Codex-style round semantics) for a model of a provider: the
     * {@code behavior.turnContinuesOnTextEnd} of the first matching model entry, else the
     * provider-level {@code tuning.turnContinuesOnTextEnd}, else false. Default false keeps today's
     * behavior (a text-only end with unfinished todos surfaces the 'send continue' banner); the flag
     * must be set explicitly to true. A nullable Boolean lets a per-model value override (including
     * an explicit false) the provider-level one.
     */
    public boolean turnContinuesOnTextEnd(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().turnContinuesOnTextEnd() != null) {
            return entry.behavior().turnContinuesOnTextEnd();
        }
        final var tuning = providerTuning(provider);
        return tuning != null && tuning.turnContinuesOnTextEnd() != null && tuning.turnContinuesOnTextEnd();
    }

    /**
     * Resolve the effective exploration-nudge round count for a model of a provider: the
     * {@code behavior.explorationNudgeRounds} of the first matching model entry when non-null and
     * non-zero, else the provider-level {@code tuning.explorationNudgeRounds} when non-null and
     * non-zero, else 0 so the caller applies the global {@code llm.explorationNudgeRounds} default.
     */
    public int explorationNudgeRounds(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().explorationNudgeRounds() != null) {
            final var rounds = entry.behavior().explorationNudgeRounds();
            if (rounds != 0) {
                return rounds;
            }
        }
        final var tuning = providerTuning(provider);
        if (tuning != null && tuning.explorationNudgeRounds() != null && tuning.explorationNudgeRounds() != 0) {
            return tuning.explorationNudgeRounds();
        }
        return 0;
    }

    /**
     * Resolve the effective iteration window cap override for a model of a provider: the
     * {@code behavior.maxIterations} of the first matching model entry when non-zero (a negative value
     * means an unbounded window), else the provider-level {@code tuning.maxIterations} when non-zero,
     * else 0 so the caller applies the global {@code llm.maxIterations} default.
     */
    public int maxIterationsOverride(final ProviderConfiguration provider, final String model) {
        final var entry = matchModel(provider, model);
        if (entry != null && entry.behavior() != null && entry.behavior().maxIterations() != null) {
            final var iterations = entry.behavior().maxIterations();
            if (iterations != 0) {
                return iterations;
            }
        }
        final var tuning = providerTuning(provider);
        if (tuning != null && tuning.maxIterations() != null && tuning.maxIterations() != 0) {
            return tuning.maxIterations();
        }
        return 0;
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
