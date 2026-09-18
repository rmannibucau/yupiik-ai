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
package io.yupiik.ia.agent.core.mcp;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

class HttpMcpClient implements McpClient {
    private final String name;
    private final String url;
    private final String apiKey;
    private final List<String> headers;
    private final long timeoutSeconds;
    private final JsonMapper mapper;
    private final ExtendedHttpClient client;
    private final AtomicLong ids = new AtomicLong();

    private volatile String sessionId;
    private volatile String protocolVersion;

    HttpMcpClient(
            final String name,
            final String url,
            final String apiKey,
            final List<String> headers,
            final long timeoutSeconds,
            final JsonMapper mapper,
            final ExtendedHttpClient client) {
        this.name = name;
        this.url = url;
        this.apiKey = apiKey;
        this.headers = headers;
        this.timeoutSeconds = timeoutSeconds;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public void initialize() throws IOException, InterruptedException {
        IOException last = null;
        for (final var candidate : SUPPORTED_PROTOCOL_VERSIONS) {
            try {
                final var res = request(
                        "initialize",
                        Map.of(
                                "protocolVersion", candidate,
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "yca", "version", "1.0.0")));
                if (res instanceof Map<?, ?> r
                        && r.get("protocolVersion") instanceof String echoed
                        && !echoed.isBlank()) {
                    protocolVersion = echoed;
                } else {
                    protocolVersion = candidate;
                }
                request("notifications/initialized", null);
                return;
            } catch (final IOException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("MCP " + name + ": empty initialize response");
    }

    @Override
    public List<McpServerTool> listTools() throws IOException, InterruptedException {
        final var res = request("tools/list", Map.of());
        final var out = new ArrayList<McpServerTool>();
        if (res instanceof Map<?, ?> m && m.get("tools") instanceof List<?> tools) {
            for (final var t : tools) {
                if (t instanceof Map<?, ?> tool) {
                    out.add(new McpServerTool(
                            String.valueOf(tool.get("name")),
                            tool.get("description") == null ? "" : String.valueOf(tool.get("description")),
                            tool.get("inputSchema") instanceof Map<?, ?> s ? castMap(s) : Map.of()));
                }
            }
        }
        return out;
    }

    @Override
    public String callTool(final String toolName, final Map<String, Object> arguments)
            throws IOException, InterruptedException {
        final var res = request("tools/call", Map.of("name", toolName, "arguments", arguments));
        if (res instanceof Map<?, ?> m) {
            if (Boolean.parseBoolean(String.valueOf(m.get("isError")))) {
                throw new IOException("MCP tool " + toolName + " failed: " + m.get("content"));
            }
            final var sb = new StringBuilder();
            if (m.get("content") instanceof List<?> content) {
                for (final var c : content) {
                    if (c instanceof Map<?, ?> cm && "text".equals(String.valueOf(cm.get("type")))) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(cm.get("text"));
                    }
                }
            }
            return sb.toString();
        }
        return String.valueOf(res);
    }

    private Object request(final String method, final Map<String, Object> params)
            throws IOException, InterruptedException {
        final var notification = method.startsWith("notifications/");
        final var message = new java.util.LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        if (!notification) {
            message.put("id", ids.incrementAndGet());
        }
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream");
        if (timeoutSeconds > 0) {
            builder.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("authorization", "Bearer " + apiKey);
        }
        if (sessionId != null) {
            builder.header("mcp-session-id", sessionId);
        }
        for (final var kv : headers == null ? List.<String>of() : headers) {
            final var i = kv.indexOf('=');
            if (i > 0) {
                builder.header(kv.substring(0, i).strip(), kv.substring(i + 1).strip());
            }
        }
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.toBytes(message)));
        final HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        response.headers().firstValue("mcp-session-id").ifPresent(s -> sessionId = s);
        if (response.statusCode() == 202
                || response.body() == null
                || response.body().isBlank()) {
            return null;
        }
        if (response.statusCode() >= 400) {
            throw new IOException("MCP " + name + " HTTP " + response.statusCode() + ": " + response.body());
        }
        final var contentType = response.headers().firstValue("content-type").orElse("");
        final Object json;
        if (contentType.contains("text/event-stream")) {
            json = parseSse(response.body(), message.get("id") instanceof Number n ? n.longValue() : -1);
        } else {
            try {
                json = mapper.fromString(Object.class, response.body());
            } catch (final RuntimeException e) {
                throw new IOException("MCP " + name + ": invalid JSON response: " + response.body());
            }
        }
        if (json instanceof Map<?, ?> m) {
            if (m.get("error") instanceof Map<?, ?> err) {
                throw new IOException("MCP " + name + " " + method + " error: " + err.get("message"));
            }
            return m.get("result");
        }
        return null;
    }

    private Object parseSse(final String body, final long expectedId) throws IOException {
        for (final var line : body.split("\n")) {
            final var trimmed = line.strip();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            final var payload = trimmed.substring(5).strip();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            final Object json;
            try {
                json = mapper.fromString(Object.class, payload);
            } catch (final RuntimeException e) {
                continue;
            }
            if (json instanceof Map<?, ?> m && m.get("id") != null && expectedId == numberOrMinusOne(m.get("id"))) {
                if (m.get("error") instanceof Map<?, ?> err) {
                    throw new IOException("MCP " + name + " error: " + err.get("message"));
                }
                return m;
            }
        }
        return null;
    }

    private static long numberOrMinusOne(final Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? -1 : Long.parseLong(o.toString());
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    @Override
    public void close() {
        // the ExtendedHttpClient is a shared application-scoped bean, do not close it here
    }
}
