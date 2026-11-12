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
package io.yupiik.ia.agent.github;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One-shot host-side, read-only context staging for credential-free reviews, not an interactive API proxy.
 * The caller owns the client and must stage before exposing the attempt directory to an executor.
 */
public final class GithubContextSnapshot {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PAGES = 10;
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private final GitHubClient github;
    private final JsonMapper json;

    public GithubContextSnapshot(final GitHubClient github, final JsonMapper json) {
        this.github = Objects.requireNonNull(github);
        this.json = Objects.requireNonNull(json);
    }

    public void stage(final Map<String, Object> task, final Path attempt) throws IOException {
        if (!(task.get("repository") instanceof String repo)
                || repo.length() > 140
                || !REPOSITORY.matcher(repo).matches()
                || repo.endsWith("/.")
                || repo.endsWith("/..")) {
            throw new IllegalArgumentException("Invalid GitHub repository");
        }
        final long number = number(task.get("number"));
        final var workspace = attempt.toAbsolutePath().normalize().resolve("workspace");
        final var target = workspace.resolve("github-context.json");
        checkDestination(workspace, target);

        final var budget = new Budget();
        final var prefix = "/repos/" + repo + "/pulls/" + number;
        final var pull = object(budget.read(prefix).body());
        final var selectedPull = fields(pull, "title", "body");
        for (final var ref : List.of("head", "base")) {
            selectedPull.put(ref, fields(object(pull.get(ref)), "sha"));
        }
        final var snapshot = new Snapshot(
                1,
                repo,
                number,
                selectedPull,
                pages(prefix + "/files", budget, "filename", "status", "patch"),
                pages(prefix + "/reviews", budget, "body", "state", "commit_id"),
                pages(prefix + "/comments", budget, "path", "line", "side", "body"),
                pages("/repos/" + repo + "/issues/" + number + "/comments", budget, "body"));
        final var bytes = encoded(snapshot);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalStateException("GitHub context exceeds encoded JSON limit");
        }

        // All HTTP, parsing and limit checks complete before creating any snapshot file.
        checkDestination(workspace, target);
        boolean created = false;
        try {
            try (final var output = Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                output.write(bytes);
            }
        } catch (final IOException e) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (final IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    private List<Map<String, Object>> pages(final String endpoint, final Budget budget, final String... names) {
        final var result = new ArrayList<Map<String, Object>>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            final var response = budget.read(endpoint + "?per_page=100" + (page == 1 ? "" : "&page=" + page));
            if (!(response.body() instanceof List<?> entries)) {
                throw new IllegalStateException("Expected GitHub context array");
            }
            for (final var entry : entries) {
                result.add(fields(object(entry), names));
            }
            if (response.next() == null) {
                return result;
            }
            if (page == MAX_PAGES) {
                throw new IllegalStateException("GitHub context exceeds pagination limit");
            }
            validateNext(response.next(), endpoint, page + 1);
        }
        throw new IllegalStateException("GitHub context exceeds pagination limit");
    }

    private void validateNext(final String next, final String endpoint, final int page) {
        final var uri = URI.create(next);
        // Enterprise API prefixes are allowed, but links never become request targets.
        // Only the next sequential page of our locally constructed endpoint is requested.
        final var query = uri.getRawQuery();
        if (uri.getRawPath() == null
                || !uri.getRawPath().endsWith(endpoint)
                || uri.getRawPath().contains("%")
                || uri.getRawPath().contains("/../")
                || uri.getRawPath().contains("/./")
                || uri.getRawFragment() != null
                || !(("per_page=100&page=" + page).equals(query) || ("page=" + page + "&per_page=100").equals(query))) {
            throw new IllegalStateException("Invalid GitHub context pagination");
        }
    }

    private static long number(final Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Expected positive integral GitHub number");
        }
        try {
            final long number = new BigDecimal(value.toString()).longValueExact();
            if (number > 0) {
                return number;
            }
        } catch (final NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected positive integral GitHub number", invalid);
        }
        throw new IllegalArgumentException("Expected positive integral GitHub number");
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Expected GitHub context object");
        }
        final var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (key instanceof String name) {
                result.put(name, item);
            }
        });
        return result;
    }

    private static Map<String, Object> fields(final Map<String, Object> source, final String... names) {
        final var result = new LinkedHashMap<String, Object>();
        for (final var name : names) {
            final var value = source.get(name);
            if (source.containsKey(name)) {
                if (value != null && !(value instanceof String) && !("line".equals(name) && value instanceof Number)) {
                    throw new IllegalStateException("Invalid GitHub context field");
                }
                result.put(name, value);
            }
        }
        return result;
    }

    private static void checkDestination(final Path workspace, final Path target) throws IOException {
        Path current = workspace.getRoot();
        for (final var segment : workspace) {
            current = current.resolve(segment);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("GitHub context requires existing non-symbolic attempt directories");
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GitHub context destination already exists");
        }
    }

    private byte[] encoded(final Snapshot value) {
        return json.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    @JsonModel
    public record Snapshot(
            @Property(documentation = "Snapshot schema version.")
            int schemaVersion,

            @Property(documentation = "Upstream repository.")
            String repo,

            @Property(documentation = "Pull request number.")
            long number,

            @Property(documentation = "Whitelisted pull metadata and head/base SHAs.")
            Map<String, Object> pull,

            @Property(documentation = "Changed files and available patches.")
            List<Map<String, Object>> files,

            @Property(documentation = "Submitted review metadata.")
            List<Map<String, Object>> reviews,

            @Property(documentation = "Inline review comments.")
            List<Map<String, Object>> comments,

            @Property(documentation = "Issue conversation.") List<Map<String, Object>> issueComments) {}

    @JsonModel
    public record ContextResponse(
            @Property(documentation = "Remote context payload measured against the staging byte budget.")
            Object payload) {}

    private final class Budget {
        private long bytes;

        private GitHubClient.Page read(final String path) {
            final var page = github.page(path);
            bytes += json.toString(new ContextResponse(page.body())).getBytes(StandardCharsets.UTF_8).length
                    - "{\"payload\":}".getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) {
                throw new IllegalStateException("GitHub context exceeds encoded JSON limit");
            }
            return page;
        }
    }
}
