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

import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

class StdioMcpClient implements McpClient {
    private final String name;
    private final List<String> command;
    private final List<String> env;
    private final JsonMapper mapper;
    private final AtomicLong ids = new AtomicLong();

    private Process process;
    private BufferedReader reader;
    private OutputStream writer;

    StdioMcpClient(final String name, final List<String> command, final List<String> env, final JsonMapper mapper) {
        this.name = name;
        this.command = command;
        this.env = env;
        this.mapper = mapper;
    }

    @Override
    public synchronized void initialize() throws IOException, InterruptedException {
        final var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        for (final var kv : env) {
            final var i = kv.indexOf('=');
            if (i > 0) {
                pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
            }
        }
        process = pb.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = process.getOutputStream();
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
                notify("notifications/initialized", null);
                return;
            } catch (final IOException e) {
                last = e;
            }
        }
        process.destroyForcibly();
        throw last == null ? new IOException("MCP " + name + ": empty initialize response") : last;
    }

    private String protocolVersion;

    @Override
    public synchronized List<McpServerTool> listTools() throws IOException, InterruptedException {
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
    public synchronized String callTool(final String toolName, final Map<String, Object> arguments)
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
        final var id = ids.incrementAndGet();
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            final Object json;
            try {
                json = mapper.fromString(Object.class, line);
            } catch (final RuntimeException e) {
                continue;
            }
            if (json instanceof Map<?, ?> m && m.get("id") instanceof Number n && n.longValue() == id) {
                if (m.get("error") instanceof Map<?, ?> err) {
                    throw new IOException("MCP " + name + " " + method + " error: " + err.get("message"));
                }
                return m.get("result");
            }
        }
        throw new IOException("MCP " + name + " closed the stream during " + method);
    }

    private void notify(final String method, final Map<String, Object> params) throws IOException {
        final var m = new LinkedHashMap<String, Object>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        if (params != null) {
            m.put("params", params);
        }
        send(m);
    }

    private void send(final Map<String, Object> message) throws IOException {
        writer.write(mapper.toString(message).getBytes(StandardCharsets.UTF_8));
        writer.write('\n');
        writer.flush();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    @Override
    public synchronized void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
