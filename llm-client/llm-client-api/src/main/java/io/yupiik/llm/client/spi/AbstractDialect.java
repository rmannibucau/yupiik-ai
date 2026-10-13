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
package io.yupiik.llm.client.spi;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.DialectNotSupportedException;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.LlmResponseParseException;
import io.yupiik.llm.client.model.LlmModel;
import io.yupiik.llm.client.model.LlmModels;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/**
 * Shared HTTP, error-classification and JSON helpers for {@link Dialect} implementations. All
 * requests go through {@link ExtendedHttpClient#sendAsync}, so the API is fully reactive while the
 * transport can be shared (or replaced) at construction time without any other change.
 */
public abstract class AbstractDialect implements Dialect {

    private static final List<String> FLAVOR_MISSING_CODES = List.of("(404)", "(405)", "(501)");

    /** Shared JSON mapper. */
    protected final JsonMapper mapper;
    /** Shared HTTP client. */
    protected final ExtendedHttpClient client;
    /** Provider endpoint and authentication settings. */
    protected final ProviderSpec provider;

    /**
     * Creates a dialect bound to a provider.
     *
     * @param mapper the JSON mapper to serialize requests and parse responses.
     * @param client the shared HTTP client.
     * @param provider the provider endpoint and authentication spec.
     */
    protected AbstractDialect(final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        this.mapper = mapper;
        this.client = client;
        this.provider = provider == null ? ProviderSpec.of(null, null) : provider;
    }

    /**
     * Send a JSON chat POST and classify non-2xx responses as exceptional completion.
     *
     * @param payload the encoded request body.
     * @return a stage completing with the successful HTTP response.
     */
    protected CompletionStage<HttpResponse<String>> post(final byte[] payload) {
        try {
            return client.sendAsync(http(payload), HttpResponse.BodyHandlers.ofString())
                    .thenCompose(this::classify);
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Send a GET models request and classify non-2xx responses as exceptional completion.
     *
     * @return a stage completing with the successful HTTP response.
     */
    protected CompletionStage<HttpResponse<String>> getModels() {
        try {
            return client.sendAsync(modelsRequest(), HttpResponse.BodyHandlers.ofString())
                    .thenCompose(this::classify);
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Send a streaming chat POST; on 2xx SSE the live publisher is returned, otherwise the collected
     * body.
     *
     * @param payload the encoded request body.
     * @param cancelled cancellation probe.
     * @return a stage completing with the stream attempt.
     */
    protected CompletionStage<StreamAttempt> openStream(final byte[] payload, final BooleanSupplier cancelled) {
        try {
            return client.sendAsync(http(payload), HttpResponse.BodyHandlers.ofPublisher())
                    .thenCompose(response -> {
                        if (response.statusCode() >= 200
                                && response.statusCode() <= 299
                                && response.headers()
                                        .firstValue("content-type")
                                        .orElse("")
                                        .toLowerCase(Locale.ROOT)
                                        .contains("text/event-stream")) {
                            return CompletableFuture.completedFuture(new StreamAttempt(
                                    response.statusCode(), response.headers(), response.body(), null));
                        }
                        return collect(response.body())
                                .thenApply(body ->
                                        new StreamAttempt(response.statusCode(), response.headers(), null, body));
                    });
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Serialize a request and send it as a raw streaming POST without any response classification.
     *
     * @param request the neutral completion request.
     * @param cancelled cancellation probe.
     * @return the raw stream attempt.
     */
    public CompletionStage<StreamAttempt> streamRaw(final LlmRequest request, final BooleanSupplier cancelled) {
        return openStream(encodeRequest(request, true), cancelled);
    }

    /**
     * Serialize a request and send it as a raw buffered POST, returning the response verbatim.
     *
     * @param request the neutral completion request.
     * @return the verbatim HTTP response.
     */
    public CompletionStage<HttpResponse<byte[]>> postRaw(final LlmRequest request) {
        try {
            return client.sendAsync(http(encodeRequest(request, false)), HttpResponse.BodyHandlers.ofByteArray());
        } catch (final RuntimeException e) {
            return failed(e);
        }
    }

    /**
     * Encode the neutral request into the dialect wire payload.
     *
     * @param request the neutral completion request.
     * @param stream whether a streaming request should be produced.
     * @return the encoded JSON payload.
     */
    protected abstract byte[] encodeRequest(LlmRequest request, boolean stream);

    /**
     * The chat route suffix appended to the provider origin, e.g. {@code /chat/completions}.
     *
     * @return the chat route suffix.
     */
    protected abstract String chatRouteSuffix();

    /**
     * Complete exceptionally with a classified circuit-breaking exception for a failed stream attempt.
     *
     * @param attempt the failed stream attempt.
     * @return a stage completing exceptionally with the classified failure.
     */
    protected CompletionStage<Flow.Publisher<LlmStreamEvent>> streamError(final StreamAttempt attempt) {
        final var body = attempt.errorBody() == null ? "" : new String(attempt.errorBody(), StandardCharsets.UTF_8);
        if (isFlavorMissing(attempt.statusCode())) {
            return failed(new DialectNotSupportedException(name() + describeBody(body)));
        }
        if (ContextWindowExceededException.isCause(attempt.statusCode(), body)) {
            return failed(new ContextWindowExceededException());
        }
        return failed(new LlmHttpFailureException(
                attempt.statusCode(),
                body,
                attempt.headers().firstValue("retry-after").orElse(null)));
    }

    /**
     * Build a chat POST request with authentication applied.
     *
     * @param payload the encoded request body.
     * @return the HTTP request.
     */
    protected HttpRequest http(final byte[] payload) {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl()))
                .timeout(provider.timeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        authenticate(builder);
        provider.headersCustomizer().accept(builder);
        return builder.build();
    }

    /**
     * Build a GET models request with authentication applied.
     *
     * @return the HTTP request.
     */
    protected HttpRequest modelsRequest() {
        final var builder = HttpRequest.newBuilder()
                .uri(URI.create(deriveModelsEndpoint(provider.baseUrl(), chatRouteSuffix())))
                .timeout(provider.timeout())
                .header("accept", "application/json")
                .GET();
        authenticate(builder);
        provider.headersCustomizer().accept(builder);
        return builder.build();
    }

    /** Derive the models endpoint from the chat endpoint by stripping {@code suffix} and appending {@code /models}. */
    private static String deriveModelsEndpoint(final String chatEndpoint, final String suffix) {
        final var uri = URI.create(chatEndpoint);
        final var path = uri.getPath();
        final var stripped = path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
        final var modelsPath = (stripped.isEmpty() ? "" : stripped) + "/models";
        return uri.getScheme() + "://" + uri.getRawAuthority() + modelsPath;
    }

    /**
     * Provider-specific authentication; default adds the OpenAI Bearer authorization. The
     * {@link ProviderSpec#headersCustomizer()} is applied by the callers of this method
     * ({@link #http(byte[])} and {@link #modelsRequest()}) <em>after</em> these defaults with
     * {@code setHeader} semantics, so caller headers override the apiKey/version ones.
     *
     * @param builder the request builder to authenticate.
     */
    protected void authenticate(final HttpRequest.Builder builder) {
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            builder.header("authorization", "Bearer " + provider.apiKey());
        }
    }

    /**
     * Parses a JSON payload into the given type.
     *
     * @param <A> the target type.
     * @param type the target class.
     * @param json the JSON payload.
     * @return the parsed value.
     */
    protected <A> A parse(final Class<A> type, final String json) {
        return mapper.fromString(type, json);
    }

    /**
     * Parses the body of a successful (2xx) HTTP response, wrapping any parsing failure into a
     * contextual {@link LlmResponseParseException} that carries the dialect name, the endpoint URL,
     * the HTTP status, the content-type and a bounded single-line body excerpt — so a malformed
     * provider body is never surfaced to callers as a bare JSON-parser message. Also detects a
     * provider error envelope ({@code {"error":{...}}} / {@code {"type":"error","error":{...}}})
     * inside a 2xx body and raises a typed error carrying the provider message instead of letting
     * it become an empty completion.
     *
     * @param <A> the target type.
     * @param type the target class.
     * @param response the successful HTTP response.
     * @return the parsed value.
     * @throws LlmResponseParseException when the body is unparseable or carries an error envelope.
     */
    protected <A> A parseResponse(final Class<A> type, final HttpResponse<String> response)
            throws LlmResponseParseException {
        final var body = response.body();
        final var providerError = providerError(body);
        if (providerError != null) {
            throw new LlmResponseParseException(
                    name(),
                    endpoint(),
                    response.statusCode(),
                    response.headers().firstValue("content-type").orElse(null),
                    excerpt(body),
                    new IllegalStateException("provider error: " + providerError));
        }
        try {
            return parse(type, body);
        } catch (final RuntimeException e) {
            throw new LlmResponseParseException(
                    name(),
                    endpoint(),
                    response.statusCode(),
                    response.headers().firstValue("content-type").orElse(null),
                    excerpt(body),
                    e);
        }
    }

    /**
     * Extracts a provider error message from a 2xx body, or null when there is none.
     *
     * @param body the response body.
     * @return the provider error message, or null.
     */
    private String providerError(final String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        final var trimmed = body.strip();
        // strict top-level detection: {"type":"error","error":{...}} (Anthropic-ish) or {"error":{...}} (OpenAI-ish)
        if (!(trimmed.startsWith("{\"type\":\"error\"") || trimmed.startsWith("{\"error\""))) {
            return null;
        }
        try {
            final Object root = parse(Map.class, trimmed);
            if (root instanceof Map<?, ?> rootMap) {
                final Object error = rootMap.get("error");
                if (error instanceof Map<?, ?> errorMap) {
                    final var message = errorMap.get("message");
                    if (message != null) {
                        return String.valueOf(message);
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            // unparseable envelope: fall through to the parse-failure path which carries the body
        }
        return null;
    }

    /**
     * The chat endpoint of this dialect, derived from the provider base URL.
     *
     * @return the chat endpoint URL.
     */
    private String endpoint() {
        return provider.baseUrl();
    }

    /**
     * A bounded single-line excerpt of a response body, so huge or binary payloads stay readable.
     *
     * @param body the response body.
     * @return the excerpt, at most 400 chars, CR/LF collapsed.
     */
    private static String excerpt(final String body) {
        if (body == null) {
            return "";
        }
        final var singleLine = body.replace("\r", " ").replace("\n", " ").strip();
        return singleLine.length() <= 400 ? singleLine : singleLine.substring(0, 400) + "...";
    }

    /**
     * Returns the value as a map, or null when it is not one.
     *
     * @param value the value to convert.
     * @return the map, or null.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> asMap(final Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /**
     * Normalize a wire {@code /models} payload (root map with a {@code data} list) into
     * {@link LlmModels}.
     *
     * @param root the parsed models payload.
     * @return the normalized model listing.
     */
    protected LlmModels toModels(final Object root) {
        final var map = asMap(root);
        final var data = map == null ? null : map.get("data");
        if (!(data instanceof List<?> list)) {
            return new LlmModels(List.of());
        }
        final var models = new ArrayList<LlmModel>(list.size());
        for (final var o : list) {
            final var m = asMap(o);
            if (m == null) {
                continue;
            }
            final var id = str(m.get("id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            models.add(new LlmModel(
                    id,
                    str(m.get("provider")),
                    number(m.get("created")),
                    str(m.get("owned_by")),
                    new LinkedHashMap<>(m)));
        }
        return new LlmModels(models);
    }

    private static Long number(final Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Long.valueOf(o.toString());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Converts an arbitrary value to its string form, null-safe.
     *
     * @param o the value to convert.
     * @return the string form, or null.
     */
    protected static String str(final Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Extracts the text of a string or OpenAI-style content part list, null-safe.
     *
     * @param content the raw content value.
     * @return the extracted text, empty when absent.
     */
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

    /**
     * Whether the status code indicates the provider does not support this dialect (404/405/501).
     *
     * @param statusCode the HTTP status code.
     * @return whether the dialect is missing.
     */
    protected static boolean isFlavorMissing(final int statusCode) {
        return FLAVOR_MISSING_CODES.contains("(" + statusCode + ")");
    }

    /**
     * Surface the provider's error body when present so the real cause (e.g. {@code Model 'x' not found})
     * is not hidden behind a generic "dialect not supported" message.
     *
     * @param body the raw response body, possibly blank.
     * @return a {@code ": "}-prefixed bounded excerpt of {@code body}, empty when it carries no useful content.
     */
    protected static String describeBody(final String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        final var trimmed = body.trim();
        final var excerpt = trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
        return ": " + excerpt;
    }

    private CompletionStage<HttpResponse<String>> classify(final HttpResponse<String> response) {
        if (response.statusCode() < 400) {
            return CompletableFuture.completedFuture(response);
        }
        final var body = response.body();
        final IOException failure;
        if (isFlavorMissing(response.statusCode())) {
            failure = new DialectNotSupportedException(name() + describeBody(body));
        } else if (ContextWindowExceededException.isCause(response.statusCode(), body)) {
            failure = new ContextWindowExceededException();
        } else {
            failure = new LlmHttpFailureException(
                    response.statusCode(),
                    body,
                    response.headers().firstValue("retry-after").orElse(null));
        }
        return failed(failure);
    }

    private static CompletionStage<byte[]> collect(final Flow.Publisher<List<ByteBuffer>> publisher) {
        final var result = new CompletableFuture<byte[]>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final List<ByteBuffer> item) {
                for (final var byteBuffer : item) {
                    final var array = new byte[byteBuffer.remaining()];
                    byteBuffer.get(array);
                    buffer.writeBytes(array);
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(buffer.toByteArray());
            }
        });
        return result;
    }

    /**
     * Completes a stage exceptionally.
     *
     * @param <A> the stage element type.
     * @param e the failure.
     * @return the failed stage.
     */
    protected static <A> CompletableFuture<A> failed(final Throwable e) {
        final var future = new CompletableFuture<A>();
        future.completeExceptionally(e);
        return future;
    }
}
