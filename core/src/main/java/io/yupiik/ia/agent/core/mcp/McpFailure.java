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

import io.yupiik.ia.agent.core.tools.ToolDiagnosticException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** MCP server detail is deliberately absent from exception messages and causes. */
class McpFailure extends IOException {
    private static final Pattern UNSAFE_SERVER_NAME = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final Pattern TERMINAL_CSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern TERMINAL_OSC = Pattern.compile("\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)");
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization)\\s*[:=]\\s*)(?:Bearer|Basic)\\s+[^\\s,;\\\"]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?\\b(?:api[_-]?key|access[_-]?token|password|secret|token)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"'&,;}]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@");

    private final String detail;
    private final boolean toolError;

    McpFailure(final String message, final String detail) {
        this(message, detail, false);
    }

    private McpFailure(final String message, final String detail, final boolean toolError) {
        super(message);
        this.detail = detail;
        this.toolError = toolError;
    }

    static McpFailure toolError(final String detail) {
        return new McpFailure("MCP tool returned an error", detail, true);
    }

    String detail() {
        return detail;
    }

    McpFailure sanitized(final String apiKey, final List<String> credentials, final String sessionId) {
        final var secrets = new ArrayList<String>();
        if (apiKey != null) {
            secrets.add(apiKey);
        }
        if (sessionId != null) {
            secrets.add(sessionId);
        }
        for (final var entry : credentials) {
            final var separator = entry.indexOf('=');
            if (separator >= 0) {
                final var value = entry.substring(separator + 1).strip();
                secrets.add(value);
                if (value.regionMatches(true, 0, "Bearer ", 0, 7) || value.regionMatches(true, 0, "Basic ", 0, 6)) {
                    secrets.add(value.substring(value.indexOf(' ') + 1));
                }
            }
        }
        return new McpFailure(getMessage(), sanitize(detail, secrets), toolError);
    }

    private static String sanitize(final String detail, final List<String> secrets) {
        var text = detail == null ? "" : detail;
        // Normalize first so injected control sequences cannot split and disguise a credential.
        text = TERMINAL_CSI.matcher(text).replaceAll("");
        text = TERMINAL_OSC.matcher(text).replaceAll("");
        text = CONTROLS.matcher(text).replaceAll("");
        // Redact before truncation so a credential crossing the bound cannot be partially exposed.
        for (final var secret : secrets.stream()
                .filter(it -> !it.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            text = text.replace(secret, "[redacted]");
        }
        text = AUTHORIZATION.matcher(text).replaceAll("$1[redacted]");
        text = SECRET_ASSIGNMENT.matcher(text).replaceAll("$1[redacted]");
        text = URL_CREDENTIALS.matcher(text).replaceAll("$1[redacted]@");
        return text.length() > 8192 ? text.substring(0, 8192) + "\n[diagnostic truncated]" : text;
    }

    static ToolDiagnosticException forUi(final String name, final Throwable failure) {
        return forUi(name, null, List.of(), failure);
    }

    static ToolDiagnosticException forUi(
            final String name, final String server, final List<Pattern> avoidOnErrorPatterns, final Throwable failure) {
        var current = failure;
        for (var depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof McpHttpException http) {
                return new ToolDiagnosticException(
                        "MCP tool " + name + " failed",
                        "MCP HTTP " + http.statusCode()
                                + (http.retryAfterMillis() == null
                                        ? ""
                                        : "; retry after " + http.retryAfterMillis() + " ms"));
            }
            if (current instanceof HttpTimeoutException) {
                return new ToolDiagnosticException("MCP tool " + name + " failed", "MCP request timed out");
            }
            if (current instanceof McpFailure mcp) {
                final var detail = mcp.detail();
                final var avoid = mcp.toolError
                        && detail != null
                        && avoidOnErrorPatterns.stream()
                                .anyMatch(it -> it.matcher(detail).find());
                return new ToolDiagnosticException(
                        "MCP tool " + name + " failed"
                                + (avoid
                                        ? ". MCP server '"
                                                + UNSAFE_SERVER_NAME
                                                        .matcher(server)
                                                        .replaceAll("_")
                                                + "' returned an error matching its configured avoidance policy. "
                                                + "Avoid further calls to this server or its other tools for the current task "
                                                + "unless access changes or the user requests a retry. Continue with alternatives."
                                        : ""),
                        detail == null || detail.isBlank() ? mcp.getMessage() : detail);
            }
        }
        // Unknown exceptions can contain arbitrary server data or credentials; do not forward them.
        return new ToolDiagnosticException("MCP tool " + name + " failed", "MCP call failed");
    }
}
