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
package io.yupiik.ia.agent.core.maven;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.json.EntryPointsRoot;
import io.yupiik.ia.agent.core.maven.json.SkillRoot;
import io.yupiik.ia.agent.core.maven.json.SuggestRoot;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@link KnowledgeLlm} backed by the yca {@link ChatClient}. Uses a single bulk,
 * JSON-Schema constrained call per batch so several artifacts are resolved in one
 * LLM round trip (low call count and cost). Silently defers to the caller whenever
 * structured output is not supported by the provider.
 */
@ApplicationScoped
public class YcaKnowledgeLlm implements KnowledgeLlm {

    private final ChatClient chatClient;
    private final JsonMapper mapper;

    public YcaKnowledgeLlm(final ChatClient chatClient, final JsonMapper mapper) {
        this.chatClient = chatClient;
        this.mapper = mapper;
    }

    protected YcaKnowledgeLlm() {
        this.chatClient = null;
        this.mapper = null;
    }

    @Override
    public boolean available() {
        final var model = chatClient.model();
        return model != null && !model.isBlank();
    }

    @Override
    public io.yupiik.maven.knowledge.config.TokenBudget tokenBudget() {
        return new io.yupiik.maven.knowledge.config.TokenBudget(chatClient.contextWindow(), chatClient.outputTokens());
    }

    @Override
    public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
        final var out = new LinkedHashMap<Gav, List<String>>();
        if (contentByGav.isEmpty()) {
            return out;
        }
        final var sb = new StringJoiner("\n\n");
        final List<Gav> order = new ArrayList<>(contentByGav.keySet());
        for (final var gav : order) {
            sb.add(gav.coordinate() + '\n' + contentByGav.get(gav));
        }
        final var prompt = "Identify the main entry points for each of these Maven artifacts. A main"
                + " entry point is a class a consumer would most likely use first: the API facade, the"
                + " module entry class, the @Service/@Config/factory/FunctionalInterface, or the main()"
                + " class. Prefer fully qualified class names. Return one object per artifact in the"
                + " same order they were provided.\n\n" + sb;
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "items",
                        Map.of(
                                "type",
                                "array",
                                "items",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "gav", Map.of("type", "string"),
                                                "entryPoints",
                                                        Map.of("type", "array", "items", Map.of("type", "string"))),
                                        "required",
                                        List.of("gav", "entryPoints")))));
        final String suffix =
                "\nAnswer only with the JSON object {\"items\":[{\"gav\": string," + " \"entryPoints\":[string]}]}.";
        try {
            final var res = chatClient.chatStructured(
                    new ChatCompletionRequest(
                            null, List.of(Message.system(prompt + suffix)), null, false, null, null, null, null, null),
                    "entry_points",
                    schema);
            if (!res.structured()) {
                return out;
            }
            final var raw = rawContent(
                    res.result().message() == null
                            ? null
                            : res.result().message().content());
            final var parsed = mapper.read(EntryPointsRoot.class, new StringReader(raw));
            if (parsed.items() != null) {
                for (final var item : parsed.items()) {
                    if (item == null || item.gav() == null) {
                        continue;
                    }
                    out.put(Gav.parse(item.gav()), item.entryPoints() == null ? List.of() : item.entryPoints());
                }
            }
            return out;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return out;
        }
    }

    @Override
    public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        final var sb = new StringJoiner("\n");
        for (final var gav : candidates) {
            sb.add(gav.coordinate());
        }
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of("gavs", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required",
                List.of("gavs"));
        try {
            final var res = chatClient.chatStructured(
                    new ChatCompletionRequest(
                            null,
                            List.of(Message.system(
                                    "Given this session/project, select the Maven artifacts (from this list) that are"
                                            + " the main entry points a coding agent should know about first. Return only"
                                            + " the chosen coordinates in the same format as received.\n\n" + sb)),
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null),
                    "suggested_gavs",
                    schema);
            if (!res.structured()) {
                return candidates;
            }
            final var raw = rawContent(
                    res.result().message() == null
                            ? null
                            : res.result().message().content());
            final var parsed = mapper.read(SuggestRoot.class, new StringReader(raw));
            final var out = new ArrayList<Gav>();
            if (parsed.gavs() != null) {
                final var wanted = new java.util.HashSet<>(parsed.gavs());
                for (final var gav : candidates) {
                    if (wanted.contains(gav.coordinate())) {
                        out.add(gav);
                    }
                }
            }
            return out;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return candidates;
        }
    }

    @Override
    public String synthesizeSkill(
            final Gav gav, final String name, final String content, final List<String> entryPoints) {
        final var schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "skill",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Markdown SKILL.md content with YAML frontmatter "
                                        + "(name, description) then sections. No surrounding markdown fence.")),
                "required",
                List.of("skill"));
        try {
            final var sb = new StringBuilder();
            if (entryPoints != null && !entryPoints.isEmpty()) {
                sb.append("\nMain entry points:\n");
                for (final var ep : entryPoints) {
                    sb.append("- ").append(ep).append('\n');
                }
            }
            final var res = chatClient.chatStructured(
                    new ChatCompletionRequest(
                            null,
                            List.of(
                                    Message.system("Write a concise SKILL.md for the artifact " + gav.coordinate()
                                            + ". Use YAML frontmatter with a `name` (short) and `description`, then a '# '"
                                            + " title and '## Entry points' (list of fully qualified classes), '## Module"
                                            + " rules' and '## Working in this module' (build commands) sections when relevant."
                                            + " Focus on what a coding agent needs to use/mount the library without"
                                            + " rediscovering it. Output only the SKILL.md content." + sb),
                                    Message.user(content == null ? "" : content)),
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null),
                    "skill",
                    schema);
            if (!res.structured()) {
                return null;
            }
            final var raw = rawContent(
                    res.result().message() == null
                            ? null
                            : res.result().message().content());
            final var parsed = mapper.read(SkillRoot.class, new StringReader(raw));
            final var skill = parsed.skill() == null ? raw : parsed.skill();
            final var stripped = skill.strip();
            if (stripped.startsWith("```")) {
                final var nl = stripped.indexOf('\n');
                return nl > 0 ? stripped.substring(nl + 1).strip() : stripped;
            }
            return stripped;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String rawContent(final Object content) {
        return content == null ? "" : String.valueOf(content);
    }
}
