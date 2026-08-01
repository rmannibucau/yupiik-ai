/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.service;

import io.yupiik.fusion.a2a.api.A2ASkill;
import io.yupiik.fusion.a2a.api.A2ASkills;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.a2a.model.fusion.OpenRpc;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toCollection;

/**
 * Turns the JSON-RPC methods flagged with {@link A2ASkill} into {@link AgentSkill} descriptors.
 * <p>
 * Everything is computed once at startup: the flag comes from the bean metadata the Fusion annotation processor
 * generates ({@code a2a.type}) and the description from the generated OpenRPC document, so a skill is described from
 * its signature only. The {@link A2ASkills} beans then complete what a signature cannot carry - the tags the
 * specification requires, the examples and the media types - and may add skills of their own.
 */
@ApplicationScoped
public class SkillService {
    /**
     * Bean metadata key {@link A2ASkill} sets, see {@code BeanMetadataAlias}.
     */
    public static final String TYPE_METADATA = "a2a.type";

    /**
     * Value of {@link #TYPE_METADATA} for a skill.
     */
    public static final String SKILL = "skill";

    private final List<AgentSkill> skills;
    private final Set<String> methodBackedIds;

    protected SkillService() {
        this.skills = List.of();
        this.methodBackedIds = Set.of();
    }

    public SkillService(final OpenRpcService openRpcService, final JsonRpcRegistry registry,
                        final List<A2ASkills> contributors) {
        final var enrichers = contributors == null ? List.<A2ASkills>of() : contributors;
        final var openrpc = openRpcService.load();

        this.methodBackedIds = openrpc.methods().values().stream()
                // a document can describe a method which is not deployed - a module on the classpath but not used -
                // so ignore the ones the registry does not know
                .filter(it -> registry.methods().containsKey(it.name()))
                .filter(it -> SKILL.equals(registry.methods().get(it.name()).metadata().getOrDefault(TYPE_METADATA, "")))
                .map(OpenRpc.JsonRpcMethod::name)
                .collect(toCollection(LinkedHashSet::new));

        final var all = new ArrayList<AgentSkill>();
        openrpc.methods().values().stream()
                .filter(it -> methodBackedIds.contains(it.name()))
                .sorted(Comparator.comparing(OpenRpc.JsonRpcMethod::name))
                .map(SkillService::toSkill)
                .map(skill -> enrich(enrichers, skill))
                .forEach(all::add);
        enrichers.stream()
                .map(A2ASkills::skills)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(skill -> enrich(enrichers, skill))
                .forEach(all::add);
        this.skills = List.copyOf(all);
    }

    /**
     * @return the skills to advertise in the agent card.
     */
    public List<AgentSkill> skills() {
        return skills;
    }

    /**
     * @param id a skill identifier.
     * @return {@code true} when {@code id} is a JSON-RPC method the built-in router can invoke - a skill contributed
     * by {@link A2ASkills#skills()} is advertised but not callable that way.
     */
    public boolean isMethodBacked(final String id) {
        return methodBackedIds.contains(id);
    }

    /**
     * @return the identifiers of the {@link A2ASkill} methods, in declaration order.
     */
    public Set<String> methodBackedIds() {
        return methodBackedIds;
    }

    /**
     * @return the only {@link A2ASkill} method identifier, {@code null} when there is none or more than one. It is what
     * makes a single skill agent work without the client having to name the skill.
     */
    public String singleMethodBackedId() {
        return methodBackedIds.size() == 1 ? methodBackedIds.iterator().next() : null;
    }

    private static AgentSkill toSkill(final OpenRpc.JsonRpcMethod method) {
        return AgentSkill.of(method.name(), method.name(), method.description(), List.of());
    }

    private static AgentSkill enrich(final List<A2ASkills> enrichers, final AgentSkill skill) {
        var current = skill;
        for (final var enricher : enrichers) {
            final var described = enricher.describe(current);
            if (described != null) {
                current = described;
            }
        }
        return current;
    }
}
