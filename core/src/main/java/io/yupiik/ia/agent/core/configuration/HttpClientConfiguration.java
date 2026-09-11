package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.Map;

public record HttpClientConfiguration(
        @Property(defaultValue = "java.util.Map.of()", documentation = "Extra headers, KEY=VALUE, applied to every request sent through the shared HTTP client (webfetch, websearch and MCP over HTTP). A user-agent header set here overrides the default Chrome user-agent.") Map<String, String> headers,
        @Property(defaultValue = "false", documentation = "Disable TLS certificate validation and hostname verification. Only intended for internal/testing endpoints, use with care.") boolean disableTlsCheck,
        @Property(defaultValue = "\"HTTP/1.1\"", documentation = "HTTP version to use: HTTP/1.1 or HTTP_2.") String version,
        @Property(defaultValue = "30", documentation = "Connect timeout in seconds.") int connectTimeoutSeconds) {

    public static HttpClientConfiguration defaultConfig() {
        return new HttpClientConfiguration(Map.of(), false, "HTTP/1.1", 30);
    }
}