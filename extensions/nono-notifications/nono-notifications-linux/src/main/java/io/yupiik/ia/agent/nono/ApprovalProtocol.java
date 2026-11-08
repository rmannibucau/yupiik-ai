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
package io.yupiik.ia.agent.nono;

import io.yupiik.fusion.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded decoder and validator for the pinned nono 0.81.0 webhook contract. */
public final class ApprovalProtocol {
    public static final int MAX_BODY = 32768;
    private static final Set<String> COMMON =
            Set.of("capability_type", "request_id", "session_id", "reason", "child_pid");
    private final JsonMapper mapper;

    public ApprovalProtocol(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Approval parse(final byte[] bytes) {
        if (bytes.length > MAX_BODY) {
            throw new IllegalArgumentException("Oversized request");
        }
        try {
            final var text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            // Use the shared mapper's JSON semantics; validate and display only this decoded object.
            if (!(mapper.fromString(Object.class, text) instanceof Map<?, ?> root)
                    || !root.keySet().equals(Set.of("backend", "request"))) {
                throw new IllegalArgumentException("Invalid envelope");
            }
            final var backend = identifier(root, "backend");
            if (!(root.get("request") instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Missing request");
            }
            final var request = new LinkedHashMap<String, Object>();
            raw.forEach((k, v) -> request.put((String) k, v));
            final var type = identifier(request, "capability_type");
            final var fields =
                    switch (type) {
                        case "capability" -> Set.of("path", "access");
                        case "network" -> Set.of("host", "port", "protocol", "resolved_ips");
                        case "endpoint" -> Set.of("route_id", "upstream", "method", "path", "rule_label");
                        case "command" -> Set.of("command", "args", "caller", "intercept_rule");
                        default -> throw new IllegalArgumentException("Unknown capability");
                    };
            if (!request.keySet().stream().allMatch(k -> COMMON.contains(k) || fields.contains(k))) {
                throw new IllegalArgumentException("Unknown field");
            }
            final var id = identifier(request, "request_id");
            final var session = identifier(request, "session_id");
            request.put("child_pid", integer(request.get("child_pid"), Integer.MAX_VALUE));
            for (final var key : fields) {
                final var value = request.get(key);
                if (key.equals("port")) {
                    request.put(key, integer(value, 65535));
                } else if (key.equals("args") || key.equals("resolved_ips")) {
                    if (!(value instanceof List<?> list)
                            || list.size() > 128
                            || !list.stream().allMatch(v -> v instanceof String)) {
                        throw new IllegalArgumentException("Invalid list");
                    }
                    request.put(key, List.copyOf(list));
                } else if (!Set.of("caller", "intercept_rule", "rule_label").contains(key) || value != null) {
                    string(request, key);
                }
            }
            if (request.get("reason") != null) {
                string(request, "reason");
            }
            if (type.equals("capability")
                    && !Set.of("Read", "Write", "ReadWrite").contains(request.get("access"))) {
                throw new IllegalArgumentException("Invalid access");
            }
            return new Approval(backend, id, session, type, Collections.unmodifiableMap(request));
        } catch (final Exception error) {
            throw new IllegalArgumentException("Invalid approval request");
        }
    }

    private long integer(final Object value, final long maximum) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException("Invalid integer");
        }
        final var integer = number.longValueExact();
        if (integer < 1 || integer > maximum) {
            throw new IllegalArgumentException("Integer out of range");
        }
        return integer;
    }

    private String identifier(final Map<?, ?> map, final String key) {
        final var value = string(map, key);
        if (value.length() > 256 || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Invalid identity");
        }
        return value;
    }

    private String string(final Map<?, ?> map, final String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank() || value.length() > 8192) {
            throw new IllegalArgumentException("Invalid string");
        }
        return value;
    }

    public record Approval(String backend, String id, String session, String type, Map<String, Object> request) {
        public String presentation() {
            final var text = new StringBuilder();
            switch (type) {
                case "capability" -> {
                    text.append("Allow filesystem access?\n");
                    field(text, "Access", request.get("access"));
                    field(text, "Path", request.get("path"));
                    text.append("\nAn open descriptor or filesystem changes can outlast this approval.\n");
                }
                case "network" -> {
                    text.append("Allow network connection?\n");
                    field(text, "Host", request.get("host"));
                    field(text, "Port", request.get("port"));
                    field(text, "Protocol", request.get("protocol"));
                    indexed(text, "Resolved IPs", request.get("resolved_ips"));
                    text.append("\nThe connection and transferred data can have lasting effects.\n");
                }
                case "endpoint" -> {
                    text.append("Allow endpoint request?\n");
                    field(text, "Route", request.get("route_id"));
                    field(text, "Upstream", request.get("upstream"));
                    field(text, "Method", request.get("method"));
                    field(text, "Path", request.get("path"));
                    field(text, "Rule", request.get("rule_label"));
                    text.append("\nThe request and transferred data can have lasting effects.\n");
                }
                case "command" -> {
                    text.append("Allow command execution?\n");
                    field(text, "Command", request.get("command"));
                    indexed(text, "Arguments", request.get("args"));
                    field(text, "Caller", request.get("caller"));
                    field(text, "Intercept rule", request.get("intercept_rule"));
                    text.append("\nThe process and its changes can outlast this approval.\n");
                }
                default -> throw new IllegalArgumentException("Unknown capability");
            }
            text.append("\nDetails\n");
            field(text, "Session", session);
            field(text, "Process", request.get("child_pid"));
            field(text, "Request", id);
            field(text, "Backend", backend);
            field(text, "Reason", request.get("reason"));
            return text.toString();
        }

        private static void field(final StringBuilder text, final String label, final Object value) {
            text.append(label).append(": ");
            if (value instanceof String string) {
                quoted(text, string);
            } else {
                text.append(value == null ? "(not provided)" : value);
            }
            text.append('\n');
        }

        private static void indexed(final StringBuilder text, final String label, final Object value) {
            final var values = (List<?>) value;
            text.append(label).append(" (").append(values.size()).append("):\n");
            for (int i = 0; i < values.size(); i++) {
                field(text, "  [" + i + "]", values.get(i));
            }
        }

        // Quote each value before layout so controls cannot forge labels or be removed by UI sanitization.
        // Escaping quotes and backslashes also keeps empty, whitespace, and literal escape arguments distinct.
        private static void quoted(final StringBuilder text, final String value) {
            text.append('"');
            for (int i = 0; i < value.length(); i++) {
                final var c = value.charAt(i);
                switch (c) {
                    case '"' -> text.append("\\\"");
                    case '\\' -> text.append("\\\\");
                    case '\n' -> text.append("\\n");
                    case '\r' -> text.append("\\r");
                    case '\t' -> text.append("\\t");
                    default -> {
                        final var kind = Character.getType(c);
                        if (kind == Character.CONTROL
                                || kind == Character.FORMAT
                                || kind == Character.SURROGATE
                                || kind == Character.LINE_SEPARATOR
                                || kind == Character.PARAGRAPH_SEPARATOR) {
                            text.append(String.format("\\u%04x", (int) c));
                        } else {
                            text.append(c);
                        }
                    }
                }
            }
            text.append('"');
        }
    }
}
