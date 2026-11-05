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

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentModelResolver;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentModelPolicy;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.SpawnSubagentToolConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** On-demand, bounded catalog of agent profiles and configured model routes. */
@ApplicationScoped
public class ListSubagentOptionsTool implements EmbeddedTool {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_OFFSET = 100_000;
    private static final int MAX_DESCRIPTION_LENGTH = 300;

    private final AgentProvider agentProvider;
    private final ProviderManager providerManager;
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final AgentModelResolver agentModelResolver;

    public ListSubagentOptionsTool(
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final AgentModelResolver agentModelResolver) {
        this.agentProvider = agentProvider;
        this.providerManager = providerManager;
        this.configuration = configuration;
        this.mapper = mapper;
        this.agentModelResolver = agentModelResolver;
    }

    public ListSubagentOptionsTool(
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper) {
        this(agentProvider, providerManager, configuration, mapper, null);
    }

    protected ListSubagentOptionsTool() {
        this.agentProvider = null;
        this.providerManager = null;
        this.configuration = null;
        this.mapper = null;
        this.agentModelResolver = null;
    }

    @Override
    public String name() {
        return "list_subagent_options";
    }

    @Override
    public String description() {
        return "Search the configured agent profiles and model routes that can be used with spawn_subagent. "
                + "Pass kind (agents, models, or all) and a small limit to keep the reply cheap; filter by a "
                + "provider id or a query substring when you already know what to look for. Call only when you "
                + "need to choose or inspect options; results are bounded and paginated.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "kind", Schemas.str("Options to list: agents, models, or all (default: all)."),
                "query",
                        Schemas.str(
                                "Optional case-insensitive substring to match names, descriptions, model ids, or providers."),
                "provider", Schemas.str("Optional provider id filter for model options."),
                "offset", Schemas.intProp("Zero-based result offset for pagination (default: 0)."),
                "limit", Schemas.intProp("Maximum results to return (default: 20, hard maximum: 50).")));
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var kind = arg(args, "kind");
        final var normalizedKind =
                kind == null || kind.isBlank() ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        if (!List.of("all", "agents", "models").contains(normalizedKind)) {
            return ToolResult.error("kind must be agents, models, or all");
        }
        final var query = arg(args, "query");
        final var provider = arg(args, "provider");
        final int offset;
        final int limit;
        try {
            offset = intArg(args.get("offset"), 0);
            limit = intArg(args.get("limit"), DEFAULT_LIMIT);
        } catch (final IllegalArgumentException invalid) {
            return ToolResult.error("offset and limit must be non-negative integers");
        }
        if (offset > MAX_OFFSET) {
            return ToolResult.error("offset must be between 0 and " + MAX_OFFSET);
        }
        if (limit < 1) {
            return ToolResult.error("limit must be a positive integer between 1 and " + MAX_LIMIT);
        }
        final var entries = entries(normalizedKind, query, provider);
        final var page =
                entries.stream().skip(offset).limit(Math.min(limit, MAX_LIMIT)).toList();
        final var result = new LinkedHashMap<String, Object>();
        result.put("options", page);
        result.put("total", entries.size());
        result.put("offset", offset);
        result.put("limit", Math.min(limit, MAX_LIMIT));
        if (offset + page.size() < entries.size()) {
            result.put("next_offset", offset + page.size());
        }
        try {
            return ToolResult.ok(mapper.toString(result));
        } catch (final RuntimeException e) {
            return ToolResult.error("Failed to serialize catalog: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> entries(final String kind, final String query, final String providerFilter) {
        final var policy = policy();
        final var search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        final var entries = new ArrayList<Map<String, Object>>();
        final var includeAgents =
                ("all".equals(kind) && (providerFilter == null || providerFilter.isBlank())) || "agents".equals(kind);
        if (includeAgents) {
            final var profileDefault = effectiveProviderDefault();
            for (final var agent : agentProvider.agents()) {
                if (!policy.allowsAgent(agent.name())) {
                    continue;
                }
                final var description = compactDescription(agent.description());
                final var resolver =
                        agentModelResolver == null ? new AgentModelResolver(providerManager) : agentModelResolver;
                final var effective = resolver.resolve(ChatSettings.EMPTY, agent, null, profileDefault);
                final var model = effective.reference();
                if (!SubagentModelPolicy.allows(policy, effective)) {
                    continue;
                }
                if (providerFilter != null
                        && !providerFilter.isBlank()
                        && !providerFilter.equals(effective.settings().providerId())) {
                    continue;
                }
                final var haystack = (agent.name() + " " + description).toLowerCase(Locale.ROOT);
                if (!search.isEmpty() && !haystack.contains(search)) {
                    continue;
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("kind", "agent");
                entry.put("name", agent.name());
                if (description != null) {
                    entry.put("description", description);
                }
                if (model != null) {
                    entry.put("model", model);
                }
                entries.add(entry);
            }
        }
        if ("all".equals(kind) || "models".equals(kind)) {
            for (final var provider : providerManager.providers()) {
                addProviderModels(entries, provider, policy, search, providerFilter);
            }
        }
        entries.sort(Comparator.comparing((Map<String, Object> it) -> String.valueOf(it.get("kind")))
                .thenComparing(it -> String.valueOf(it.get("name"))));
        return entries;
    }

    private void addProviderModels(
            final List<Map<String, Object>> entries,
            final ProviderConfiguration provider,
            final SpawnSubagentToolConfiguration policy,
            final String search,
            final String providerFilter) {
        final var providerId = providerId(provider);
        if (!policy.allowsProvider(providerId)
                || providerFilter != null && !providerFilter.isBlank() && !providerFilter.equals(providerId)) {
            return;
        }
        final var models = new LinkedHashSet<String>();
        if (provider.model() != null && !provider.model().isBlank()) {
            models.add(provider.model().strip());
        }
        if (provider.defaultSubagentModel() != null
                && !provider.defaultSubagentModel().isBlank()) {
            final var configured = provider.defaultSubagentModel().strip();
            final var routed = configured.startsWith("$")
                    ? configured
                    : Objects.requireNonNullElseGet(
                            providerManager.toRoutedReference(configured), () -> "$" + providerId + "/" + configured);
            final var resolved = providerManager.resolve(routed);
            final var target = resolved.provider() == null ? providerId : providerId(resolved.provider());
            addModel(entries, policy, search, providerFilter, target, resolved.model());
        }
        if (provider.models() != null) {
            provider.models().stream()
                    .map(m -> m == null ? null : m.model())
                    .filter(m -> m != null && !m.isBlank())
                    .map(String::strip)
                    .forEach(models::add);
        }
        for (final var model : models) {
            addModel(entries, policy, search, providerFilter, providerId, model);
        }
    }

    private void addModel(
            final List<Map<String, Object>> entries,
            final SpawnSubagentToolConfiguration policy,
            final String search,
            final String providerFilter,
            final String providerId,
            final String model) {
        if (model == null || model.isBlank() || !policy.allowsProvider(providerId)) {
            return;
        }
        if (providerFilter != null && !providerFilter.isBlank() && !providerFilter.equals(providerId)) {
            return;
        }
        final var reference = "$" + providerId + "/" + model;
        if (!policy.allowsModel(reference) && !policy.allowsModel(model)) {
            return;
        }
        final var haystack = (providerId + " " + model + " " + reference).toLowerCase(Locale.ROOT);
        if (!search.isEmpty() && !haystack.contains(search)) {
            return;
        }
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("kind", "model");
        entry.put("name", model);
        entry.put("provider", providerId);
        entry.put("reference", reference);
        entries.add(entry);
    }

    private static String providerId(final ProviderConfiguration provider) {
        return provider.id() == null || provider.id().isBlank() ? "default" : provider.id();
    }

    private String effectiveProviderDefault() {
        return providerManager == null ? null : providerManager.effectiveSubagentDefault(ChatSettings.EMPTY);
    }

    private SpawnSubagentToolConfiguration policy() {
        final var configured =
                configuration.tools() == null ? null : configuration.tools().spawnSubagent();
        return configured == null ? new SpawnSubagentToolConfiguration(null, null, null) : configured;
    }

    private static String compactDescription(final String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        final var value = description.strip();
        return value.length() <= MAX_DESCRIPTION_LENGTH
                ? value
                : value.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }

    private static int intArg(final Object value, final int fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        final long numeric;
        try {
            if (number instanceof BigDecimal decimal) {
                numeric = decimal.longValueExact();
            } else if (number instanceof BigInteger integer) {
                numeric = integer.longValueExact();
            } else if (number instanceof Byte
                    || number instanceof Short
                    || number instanceof Integer
                    || number instanceof Long) {
                numeric = number.longValue();
            } else {
                final var doubleValue = number.doubleValue();
                if (!Double.isFinite(doubleValue)
                        || doubleValue != Math.rint(doubleValue)
                        || doubleValue < 0
                        || doubleValue > Integer.MAX_VALUE) {
                    throw new ArithmeticException("not a bounded integer");
                }
                numeric = (long) doubleValue;
            }
        } catch (final ArithmeticException invalid) {
            throw new IllegalArgumentException("pagination values must be non-negative integers");
        }
        if (numeric < 0 || numeric > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pagination values must be non-negative integers");
        }
        return (int) numeric;
    }
}
