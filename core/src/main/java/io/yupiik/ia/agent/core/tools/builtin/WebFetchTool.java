package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.ia.agent.core.html.HtmlLightParser;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optInt;

@ApplicationScoped
public class WebFetchTool implements EmbeddedTool {
    private static final int MAX_BYTES = 200_000;
    private static final int MAX_OUTPUT = 60_000;

    private final ExtendedHttpClient client;
    private final HtmlLightParser htmlParser;

    public WebFetchTool(final ExtendedHttpClient client, final HtmlLightParser htmlParser) {
        this.client = client;
        this.htmlParser = htmlParser;
    }

    protected WebFetchTool() {
        this.client = null;
        this.htmlParser = null;
    }

    @Override
    public String name() {
        return "webfetch";
    }

    @Override
    public String description() {
        return "Fetch an HTTP(S) URL and return its content as plain text, markdown or raw html (default markdown). Use for documentation, API descriptions or any public web resource.";
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
        return Schemas.object(Map.of(
                "url", Schemas.str("The URL to fetch."),
                "format", Schemas.str("Output format: text, markdown or html. Default markdown."),
                "timeout_seconds", Schemas.intProp("Request timeout in seconds, default 30.")), "url");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var url = arg(args, "url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("url is required");
        }
        final var format = optFormat(arg(args, "format"));
        final var timeout = (int) Math.max(1, optInt(args, "timeout_seconds", 30));
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (final IllegalArgumentException e) {
            return ToolResult.error("Invalid URL: " + url);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            return ToolResult.error("Only http/https URLs are supported");
        }
        final var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeout))
                .header("accept", "text/html,application/xhtml+xml,text/plain,application/json,*/*;q=0.8")
                .GET()
                .build();
        try {
            final var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                return ToolResult.error("HTTP " + response.statusCode() + " for " + url);
            }
            final var body = decode(response);
            final var contentType = response.headers().firstValue("content-type").orElse("text/html");
            final String out;
            if ("html".equals(format)) {
                out = body;
            } else if (!isHtml(contentType) && "text".equals(format)) {
                out = body.strip();
            } else if (!isHtml(contentType)) {
                out = body.strip();
            } else if ("text".equals(format)) {
                out = htmlParser.toText(body);
            } else {
                out = htmlParser.toMarkdown(body);
            }
            return ToolResult.ok(cap(out, MAX_OUTPUT));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while fetching " + url);
        } catch (final Exception e) {
            return ToolResult.error("Cannot fetch " + url + ": " + e.getMessage());
        }
    }

    private static String optFormat(final String raw) {
        if (raw == null) {
            return "markdown";
        }
        final var lower = raw.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "text", "txt", "html", "htm" -> lower.startsWith("text") ? "text" : "html";
            default -> "markdown";
        };
    }

    private static boolean isHtml(final String contentType) {
        final var ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("html") || ct.contains("xhtml");
    }

    private static String decode(final HttpResponse<byte[]> response) {
        final var charset = response.headers().firstValue("content-type").orElse("")
                .replaceAll("(?i).*charset\\s*=\\s*[\"']?([^\"';\\s]+).*", "$1");
        if (charset != null && !charset.isEmpty() && !charset.equals(response.headers().firstValue("content-type").orElse(""))) {
            try {
                return new String(response.body(), java.nio.charset.Charset.forName(charset));
            } catch (final RuntimeException e) {
                // fall through
            }
        }
        final var raw = new String(response.body(), StandardCharsets.UTF_8);
        // crude UTF-8 BOM / meta detection fallback to latin1 if it looks binary
        return raw;
    }

    private static String cap(final String s, final int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n[... truncated ...]";
    }
}