package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public ProviderManager(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    protected ProviderManager() {
        this.configuration = null;
        this.mapper = null;
    }

    /** A provider matched with a concrete model name resolved from a model reference. */
    public record ResolvedModel(ProviderConfiguration provider, String model) {
    }

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
     * {@code providerSpecificOptions} is blank or does not start with '{'.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> providerOptions(final ProviderConfiguration provider) {
        if (provider == null || provider.providerSpecificOptions() == null
                || !provider.providerSpecificOptions().startsWith("{")) {
            return Collections.emptyMap();
        }
        try {
            final var parsed = mapper.fromString(Object.class, provider.providerSpecificOptions());
            if (!(parsed instanceof Map)) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new HashMap<>((Map<String, Object>) parsed));
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Invalid providerSpecificOptions JSON: " + e.getMessage(), e);
        }
    }
}