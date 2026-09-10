package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Shared HTTP, error-classification and JSON helpers for the {@link LlmClient} implementations.
 * Instances are single-shot per {@link ProviderConfiguration} and own an {@link ExtendedHttpClient}.
 */
abstract sealed class AbstractLlmClient implements LlmClient
        permits AnthropicMessagesClient, OpenAICompletionClient, OpenAIResponsesClient {

    private static final List<String> FLAVOR_MISSING_CODES = List.of("(404)", "(405)", "(501)");

    protected final JsonMapper mapper;
    protected final ExtendedHttpClient client;

    protected AbstractLlmClient(final JsonMapper mapper) {
        this.mapper = mapper;
        this.client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setDelegate(java.net.http.HttpClient.newHttpClient()));
    }

    @Override
    public void close() {
        client.close();
    }

    protected BufferedReader openStream(final ProviderConfiguration provider, final HttpRequest httpRequest,
                                       final BooleanSupplier cancelled) throws IOException, InterruptedException {
        final var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            final String body;
            try (var is = response.body()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (isFlavorMissing(response.statusCode())) {
                throw new FlavorMissingException(flavor() == ApiFlavor.RESPONSES ? "responses" : "chat");
            }
            if (ContextWindowExceededException.isCause(response.statusCode(), body)) {
                throw new ContextWindowExceededException();
            }
            throw new IOException("LLM request failed (" + response.statusCode() + "): " + body + retryAfter(response));
        }
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    protected HttpResponse<String> send(final ProviderConfiguration provider, final HttpRequest httpRequest)
            throws IOException {
        final HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return response;
    }

    protected HttpRequest http(final ProviderConfiguration provider, final byte[] payload) {
        final var endpoints = LlmEndpoints.endpoints(provider.baseUrl(), flavor());
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoints.chat()))
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        authenticate(provider, builder);
        return builder.build();
    }

    /** A GET request to the provider models endpoint with authentication headers applied. */
    protected HttpRequest modelsRequest(final ProviderConfiguration provider) {
        final var endpoints = LlmEndpoints.endpoints(provider.baseUrl(), flavor());
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoints.models()))
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .header("accept", "application/json")
                .GET();
        authenticate(provider, builder);
        return builder.build();
    }

    /** Provider-specific authentication headers; default adds the OpenAI Bearer authorization. */
    protected void authenticate(final ProviderConfiguration provider, final HttpRequest.Builder builder) {
        builder.header("authorization", "Bearer " + provider.resolveApiKey());
    }

    protected <A> A parse(final Class<A> type, final String json) {
        return mapper.fromString(type, json);
    }

    protected Object parse(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.fromString(Object.class, json);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> asMap(final Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    protected int number(final Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(o.toString());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    protected static String str(final Object o) {
        return o == null ? null : o.toString();
    }

    protected static String textOf(final Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            final var sb = new StringBuilder();
            for (final var p : parts) {
                if (p instanceof Map<?, ?> m && m.get("text") != null) {
                    sb.append(m.get("text"));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }

    protected boolean isFlavorMissing(final int statusCode) {
        return FLAVOR_MISSING_CODES.contains("(" + statusCode + ")");
    }

    protected static String retryAfter(final HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(v -> " retry-after=" + v).orElse("");
    }
}