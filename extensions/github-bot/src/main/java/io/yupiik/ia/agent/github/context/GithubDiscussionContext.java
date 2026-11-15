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
package io.yupiik.ia.agent.github.context;

import io.yupiik.ia.agent.github.client.GitHubClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed task discussion authority; neither adapters nor callers can supply an endpoint or repository. */
public final class GithubDiscussionContext {
    private final GitHubClient github;
    private final String repository;
    private final long number;

    public GithubDiscussionContext(final GitHubClient github, final String repository, final long number) {
        if (repository == null
                || !repository.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*")
                || repository.endsWith("/.")
                || repository.endsWith("/..")
                || number < 1) {
            throw new IllegalArgumentException("Invalid fixed context task");
        }
        this.github = github;
        this.repository = repository;
        this.number = number;
    }

    public Object read(final String operation, final int page) {
        if (operation == null || page < 1 || page > 10) {
            throw new IllegalArgumentException("INVALID_CONTEXT_PAGE: pages range from 1 through 10");
        }
        final var pull = "/repos/" + repository + "/pulls/" + number;
        final String endpoint;
        final String[] fields;
        switch (operation) {
            case "pull" -> {
                if (page != 1) {
                    throw new IllegalArgumentException("Pull is not paginated");
                }
                final var raw = github.get(pull);
                final var result = select(raw, "id", "node_id", "number", "title", "body", "state");
                if (!(raw instanceof Map<?, ?> value)) {
                    throw new IllegalArgumentException("Expected PR object");
                }
                result.put("head", select(value.get("head"), "sha"));
                result.put("base", select(value.get("base"), "sha"));
                return result;
            }
            case "files" -> {
                endpoint = pull + "/files";
                fields = new String[] {"sha", "filename", "previous_filename", "status", "patch"};
            }
            case "reviews" -> {
                endpoint = pull + "/reviews";
                fields = new String[] {"id", "node_id", "body", "state", "commit_id", "submitted_at"};
            }
            case "reviewComments" -> {
                endpoint = pull + "/comments";
                fields = new String[] {
                    "id", "node_id", "path", "line", "side", "body", "commit_id", "in_reply_to_id", "updated_at"
                };
            }
            case "issueComments" -> {
                endpoint = "/repos/" + repository + "/issues/" + number + "/comments";
                fields = new String[] {"id", "node_id", "body", "updated_at"};
            }
            default -> throw new IllegalArgumentException("UNKNOWN_CONTEXT_OPERATION");
        }
        final var response = github.page(endpoint + "?per_page=100&page=" + page);
        if (!(response.body() instanceof List<?> entries) || entries.size() > 100) {
            throw new IllegalArgumentException("Expected bounded list");
        }
        final var result = new ArrayList<Map<String, Object>>();
        for (final var entry : entries) {
            result.add(select(entry, fields));
        }
        // Links are metadata only, never request targets. The next request is always locally constructed.
        return Map.of(
                "entries",
                result,
                "page",
                page,
                "complete",
                response.next() == null,
                "pageLimitReached",
                page == 10 && response.next() != null);
    }

    private static Map<String, Object> select(final Object value, final String... fields) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("Expected object");
        }
        final var result = new LinkedHashMap<String, Object>();
        for (final var field : fields) {
            final var scalar = source.get(field);
            if (scalar == null || scalar instanceof String || scalar instanceof Number || scalar instanceof Boolean) {
                result.put(field, scalar);
            }
        }
        return result;
    }
}
