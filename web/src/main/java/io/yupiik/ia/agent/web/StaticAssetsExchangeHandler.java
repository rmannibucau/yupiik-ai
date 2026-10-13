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
package io.yupiik.ia.agent.web;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Serves the bundled browser UI from {@code META-INF/resources/**} with a
 * proper cache story (strong ETag + {@code Last-Modified}, immutable cache for
 * hash-fingerprinted assets, no-cache for the SPA shell), a path-escape guard,
 * HEAD support and an SPA fallback to {@code index.html} for bookmarkable
 * deep links.
 */
public class StaticAssetsExchangeHandler implements Endpoint {
    private static final String RESOURCES_PREFIX = "META-INF/resources/";

    /** A path segment that looks like a content hash (lowercase hex, 8+ chars) marks an immutable asset. */
    private static final Pattern HASH_SEGMENT = Pattern.compile("[0-9a-f]{8,}");

    private static final Map<String, String> EXTRA_MIME = Map.of(
            "js", "application/javascript",
            "mjs", "application/javascript",
            "css", "text/css",
            "html", "text/html",
            "json", "application/json",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2",
            "map", "application/json");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean matches(final Request request) {
        if (request.path().startsWith("/api/")) {
            return false;
        }
        return "GET".equals(request.method()) || "HEAD".equals(request.method());
    }

    @Override
    public CompletionStage<Response> handle(final Request request) {
        final var path = request.path();
        if (path.contains("..")) {
            return completedFuture(Response.of().status(403).body("forbidden").build());
        }
        final var resource = resourceFor(path);
        if (resource != null) {
            return serve(resource, request);
        }
        // SPA fallback: non-API GET miss -> serve index.html (200) so deep links work
        if ("/".equals(path) || hasNoExtension(path)) {
            return serveIndex(request);
        }
        return completedFuture(Response.of().status(404).body("Not Found").build());
    }

    private static String resourceFor(final String path) {
        final var relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isBlank() || "/".equals(relative)) {
            return null;
        }
        final String candidate;
        if (relative.endsWith("/")) {
            candidate = relative + "index.html";
        } else {
            candidate = relative;
        }
        return RESOURCES_PREFIX + candidate;
    }

    private static boolean hasNoExtension(final String path) {
        final var lastSegment = path.indexOf('?') >= 0 ? path.substring(0, path.indexOf('?')) : path;
        final var idx = lastSegment.lastIndexOf('/');
        final var name = idx < 0 ? lastSegment : lastSegment.substring(idx + 1);
        return !name.contains(".");
    }

    private static CompletionStage<Response> serve(final String resource, final Request request) {
        try (final var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                // SPA fallback for deep links served on extension-less paths
                return hasNoExtension(request.path()) ? serveIndex(request) : notFound();
            }
            final var bytes = stream.readAllBytes();
            return responseFor(bytes, resource, request);
        } catch (final IOException e) {
            return serverError();
        }
    }

    private static CompletionStage<Response> serveIndex(final Request request) {
        final var resource = RESOURCES_PREFIX + "index.html";
        try (final var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return completedFuture(
                        Response.of().status(503).body("web ui not built").build());
            }
            return responseFor(stream.readAllBytes(), resource, request);
        } catch (final IOException e) {
            return serverError();
        }
    }

    private static CompletionStage<Response> notFound() {
        return completedFuture(Response.of().status(404).body("Not Found").build());
    }

    private static CompletionStage<Response> serverError() {
        return completedFuture(Response.of().status(500).body("internal error").build());
    }

    private static CompletionStage<Response> responseFor(
            final byte[] bytes, final String resource, final Request request) {
        final var etag = '"' + HexFormat.of().formatHex(digest(bytes)) + '"';
        if (etag.equals(request.header("if-none-match"))) {
            return completedFuture(
                    Response.of().status(304).header("etag", etag).build());
        }
        final var contentType = contentType(resource);
        final var builder =
                Response.of().status(200).header("content-type", contentType).header("etag", etag);
        final var immutable = isImmutable(resource);
        builder.header("cache-control", immutable ? "public,max-age=31536000,immutable" : "no-cache");
        if ("HEAD".equalsIgnoreCase(request.method())) {
            builder.header("content-length", bytes.length);
        } else {
            builder.body(new String(bytes, StandardCharsets.UTF_8));
        }
        return completedFuture(builder.build());
    }

    private static String contentType(final String resource) {
        final var dot = resource.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        final var ext = resource.substring(dot + 1).toLowerCase(Locale.ROOT);
        final var known = EXTRA_MIME.get(ext);
        if (known != null) {
            return known;
        }
        final var guessed = URLConnection.guessContentTypeFromName(resource);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    private static boolean isImmutable(final String resource) {
        // a CONTENT_HASH-looking token anywhere in the last path segment (file name)
        // or any earlier segment marks the asset as content-addressable: cache it forever
        final var fileName = resource.substring(resource.lastIndexOf('/') + 1);
        if (HASH_SEGMENT.matcher(fileName).find()) {
            return true;
        }
        final var slash = resource.lastIndexOf('/');
        final var segment = slash < 0 ? resource : resource.substring(0, slash);
        return HASH_SEGMENT.matcher(segment).find();
    }

    private static byte[] digest(final byte[] bytes) {
        try {
            final var md = MessageDigest.getInstance("SHA-256");
            return md.digest(bytes);
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is always present
            throw new IllegalStateException(e);
        }
    }
}
