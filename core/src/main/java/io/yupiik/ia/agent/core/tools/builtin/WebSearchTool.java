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

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optInt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.WebSearchConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class WebSearchTool implements EmbeddedTool {
    private static final int TIMEOUT_SECONDS = 60;

    private final ExtendedHttpClient client;
    private final JsonMapper mapper;
    private final CodingAgentConfiguration configuration;

    public WebSearchTool(
            final ExtendedHttpClient client, final JsonMapper mapper, final CodingAgentConfiguration configuration) {
        this.client = client;
        this.mapper = mapper;
        this.configuration = configuration;
    }

    protected WebSearchTool() {
        this.client = null;
        this.mapper = null;
        this.configuration = null;
    }

    @Override
    public String name() {
        return "websearch";
    }

    @Override
    public String description() {
        return "Search the web and return a list of results with title, url and a snippet. Uses the configured web search provider (exa or parallel).";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "query", Schemas.str("The search query."),
                        "num_results", Schemas.intProp("Number of results to return, default 8.")),
                "query");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var query = arg(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("query is required");
        }
        final var config = configuration.tools().websearch();
        final var numResults = (int) Math.max(1, optInt(args, "num_results", config.numResults()));
        try {
            return ToolResult.ok(search(config, query, numResults));
        } catch (final Exception e) {
            return ToolResult.error("Web search failed: " + e.getMessage());
        }
    }

    private String search(final WebSearchConfiguration config, final String query, final int numResults)
            throws IOException, InterruptedException {
        final var provider = WebSearchConfiguration.Provider.of(config.provider());
        final String url;
        final String tool;
        final String apiKey;
        final Map<String, Object> arguments;
        if (provider == WebSearchConfiguration.Provider.PARALLEL) {
            url = config.parallelUrl();
            tool = "web_search";
            apiKey = key(config.apiKey(), "PARALLEL_API_KEY");
            arguments = Map.of("objective", query, "search_queries", List.of(query), "model_name", "opencode");
        } else {
            url = config.exaUrl();
            tool = "web_search_exa";
            apiKey = key(config.apiKey(), "EXA_API_KEY");
            arguments = new LinkedHashMap<>();
            arguments.put("query", query);
            arguments.put("type", "auto");
            arguments.put("numResults", numResults);
            arguments.put("livecrawl", "fallback");
            arguments.put("textLengthRange", Map.of("minLength", 20));
        }
        final var target = apiKey == null || apiKey.isBlank()
                ? url
                : url + (url.contains("?") ? "&" : "?") + "exaApiKey=" + apiKey;
        final var response = call(target, tool, arguments, apiKey);
        if (provider == WebSearchConfiguration.Provider.PARALLEL) {
            return formatParallel(response);
        }
        return formatExa(response);
    }

    private String call(final String url, final String tool, final Map<String, Object> arguments, final String apiKey)
            throws IOException, InterruptedException {
        final var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", "tools/call");
        message.put("params", Map.of("name", tool, "arguments", arguments));
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.toBytes(message)));
        final var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        final var contentType = response.headers().firstValue("content-type").orElse("");
        final Object json;
        if (contentType.contains("text/event-stream")) {
            json = parseSse(response.body());
        } else {
            try {
                json = mapper.fromString(Object.class, response.body());
            } catch (final RuntimeException e) {
                throw new IOException("Invalid response: " + response.body());
            }
        }
        if (json instanceof Map<?, ?> m && m.get("result") instanceof Map<?, ?> result) {
            return mapper.toString(result);
        }
        return json == null ? "" : String.valueOf(json);
    }

    private Object parseSse(final String body) {
        Object result = null;
        for (final var line : body.split("\n")) {
            final var trimmed = line.strip();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            final var payload = trimmed.substring(5).strip();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                final var json = mapper.fromString(Object.class, payload);
                if (json instanceof Map<?, ?> m && m.get("result") != null) {
                    result = m.get("result");
                }
            } catch (final RuntimeException e) {
                // skip non-JSON SSE data
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String formatExa(final String raw) {
        Object result;
        try {
            result = mapper.fromString(Object.class, raw);
        } catch (final RuntimeException e) {
            return raw;
        }
        if (!(result instanceof Map<?, ?> root)) {
            return raw;
        }
        final var sb = new StringBuilder();
        final var contents = root.get("contents");
        if (contents instanceof List<?> list) {
            for (final var item : list) {
                if (item instanceof Map<?, ?> m) {
                    formatResult(sb, str(m.get("title")), str(m.get("url")), str(m.get("text")));
                }
            }
            return sb.isEmpty() ? "No results" : sb.toString().strip();
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    private String formatParallel(final String raw) {
        Object result;
        try {
            result = mapper.fromString(Object.class, raw);
        } catch (final RuntimeException e) {
            return raw;
        }
        if (!(result instanceof Map<?, ?> root)) {
            return raw;
        }
        final var sb = new StringBuilder();
        final var responses = root.get("responses");
        if (responses instanceof List<?> list) {
            for (final var resp : list) {
                if (!(resp instanceof Map<?, ?> m)) {
                    continue;
                }
                final var message = m.get("message");
                if (message instanceof Map<?, ?> msg) {
                    final var content = msg.get("content");
                    if (content instanceof List<?> parts) {
                        for (final var part : parts) {
                            if (part instanceof Map<?, ?> p && "text".equals(str(p.get("type")))) {
                                sb.append(str(p.get("text"))).append("\n\n");
                            }
                        }
                    }
                }
            }
        }
        if (sb.isEmpty()) {
            formatResult(sb, "", "", raw);
        }
        return sb.toString().strip();
    }

    private void formatResult(final StringBuilder sb, final String title, final String url, final String snippet) {
        sb.append("- ");
        if (!title.isBlank()) {
            sb.append(title).append(' ');
        }
        if (!url.isBlank()) {
            sb.append('(').append(url).append(')');
        }
        if (!snippet.isBlank()) {
            sb.append('\n').append(snippet);
        }
        sb.append('\n');
    }

    private static String str(final Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String key(final String configured, final String env) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        final var fromEnv = System.getenv(env);
        return fromEnv == null || fromEnv.isBlank() ? "" : fromEnv;
    }
}
