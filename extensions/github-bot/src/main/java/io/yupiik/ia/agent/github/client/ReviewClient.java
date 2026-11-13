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
package io.yupiik.ia.agent.github.client;

import static io.yupiik.ia.agent.github.client.GraphqlResponses.cursor;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.invalid;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.number;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.object;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.positive;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.positiveDecimal;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.repository;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.repositoryName;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.text;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Batched review-summary discovery with validated identities and bounded, immutable pages. */
public final class ReviewClient {
    public static final int BATCH_SIZE = 10;
    public static final int PULL_PAGE_SIZE = 10;
    public static final int REVIEW_PAGE_SIZE = 100;

    // Keep the REST-compatible numeric receipt key without the deprecated 32-bit review databaseId.
    private static final String REVIEW =
            "databaseId:fullDatabaseId body submittedAt state url author { __typename login"
                    + " ... on User { databaseId } ... on Bot { databaseId } }";
    private static final String PAGE = "pageInfo { hasNextPage endCursor }";
    private final GitHubClient github;

    public ReviewClient(final GitHubClient github) {
        this.github = Objects.requireNonNull(github);
    }

    /** Fetches one discovery page per repository in one request; any invalid member fails the whole batch. */
    public Map<String, Page<Pull>> pulls(final List<Discovery> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > BATCH_SIZE) {
            throw new IllegalArgumentException("Expected between 1 and 10 repositories");
        }
        final var batch = new ArrayList<Discovery>(requests);
        final var seen = new HashSet<String>();
        for (final var request : batch) {
            if (request == null) {
                throw new IllegalArgumentException("Missing discovery request");
            }
            repository(request.repository());
            cursor(request.after());
            if (!seen.add(request.repository().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate repository");
            }
        }
        final var variables = new LinkedHashMap<String, Object>();
        final var declarations = new StringBuilder();
        final var selections = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            final var request = batch.get(i);
            final var parts = request.repository().split("/");
            if (i > 0) {
                declarations.append(',');
            }
            declarations
                    .append("$owner")
                    .append(i)
                    .append(":String!,$name")
                    .append(i)
                    .append(":String!,$after")
                    .append(i)
                    .append(":String");
            selections
                    .append(" r")
                    .append(i)
                    .append(":repository(owner:$owner")
                    .append(i)
                    .append(",name:$name")
                    .append(i)
                    .append(") { nameWithOwner pullRequests(states:[OPEN],first:10,after:$after")
                    .append(i)
                    .append(
                            ",orderBy:{field:UPDATED_AT,direction:DESC}) { nodes { number state reviews(first:100) { nodes { ")
                    .append(REVIEW)
                    .append(" } ")
                    .append(PAGE)
                    .append(" } } ")
                    .append(PAGE)
                    .append(" } }");
            variables.put("owner" + i, parts[0]);
            variables.put("name" + i, parts[1]);
            variables.put("after" + i, request.after());
        }
        final var data = execute("query ReviewDiscovery(" + declarations + ") {" + selections + " }", variables);
        final var result = new LinkedHashMap<String, Page<Pull>>();
        for (int i = 0; i < batch.size(); i++) {
            final var request = batch.get(i);
            final var repo = checkedRepository(data.get("r" + i), request.repository());
            result.put(
                    request.repository(),
                    page(repo.get("pullRequests"), request.after(), PULL_PAGE_SIZE, node -> pull(node, null)));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Reads a review continuation together with its current pull state, without replaying the request. */
    public Pull reviews(final String repository, final int number, final String after) {
        repository(repository);
        cursor(after);
        if (number <= 0) {
            throw new IllegalArgumentException("Expected positive pull number");
        }
        final var parts = repository.split("/");
        final var variables = new LinkedHashMap<String, Object>();
        variables.put("owner", parts[0]);
        variables.put("name", parts[1]);
        variables.put("number", number);
        variables.put("after", after);
        final var data = execute(
                "query ReviewContinuation($owner:String!,$name:String!,$number:Int!,$after:String) {"
                        + " repository(owner:$owner,name:$name) { nameWithOwner pullRequest(number:$number) {"
                        + " number state reviews(first:100,after:$after) { nodes { " + REVIEW + " } " + PAGE
                        + " } } } }",
                variables);
        final var repo = checkedRepository(data.get("repository"), repository);
        final var node = object(repo.get("pullRequest"));
        if (number(node.get("number")) != number) {
            throw invalid();
        }
        return pull(node, after);
    }

    private Map<?, ?> execute(final String query, final Map<String, Object> variables) {
        return object(object(github.graphql(query, variables)).get("data"));
    }

    private Map<?, ?> checkedRepository(final Object value, final String expected) {
        final var repo = object(value);
        if (!expected.equalsIgnoreCase(repositoryName(repo.get("nameWithOwner")))) {
            throw invalid();
        }
        return repo;
    }

    private Pull pull(final Object value, final String after) {
        final var node = object(value);
        final var number = number(node.get("number"));
        final var state = text(node.get("state"));
        return new Pull(
                number,
                state,
                "OPEN".equals(state)
                        ? page(node.get("reviews"), after, REVIEW_PAGE_SIZE, this::review)
                        : new Page<>(List.of(), null));
    }

    private Review review(final Object value) {
        final var node = object(value);
        final var submittedAt = node.get("submittedAt");
        Instant instant = null;
        if (submittedAt != null) {
            try {
                instant = Instant.parse(text(submittedAt));
            } catch (final DateTimeException e) {
                throw invalid();
            }
        }
        final var id = positiveDecimal(node.get("databaseId"));
        return new Review(
                id,
                text(node.get("body")),
                instant,
                text(node.get("state")),
                text(node.get("url")),
                actor(node.get("author")));
    }

    private Actor actor(final Object value) {
        if (value == null) {
            return new Actor(null, "", "Deleted");
        }
        final var node = object(value);
        final var type = text(node.get("__typename"));
        final var login = text(node.get("login"));
        final var id = "User".equals(type) || ("Bot".equals(type) && node.get("databaseId") != null)
                ? positive(node.get("databaseId"))
                : null;
        return new Actor(id, login, type);
    }

    private <T> Page<T> page(
            final Object value, final String after, final int limit, final Function<Object, T> mapper) {
        final var connection = object(value);
        if (!(connection.get("nodes") instanceof List<?> nodes) || nodes.size() > limit) {
            throw invalid();
        }
        final var info = object(connection.get("pageInfo"));
        if (!(info.get("hasNextPage") instanceof Boolean more)) {
            throw invalid();
        }
        final var end = info.get("endCursor");
        if (end != null && !(end instanceof String)) {
            throw invalid();
        }
        final var next = more ? text(end) : null;
        if (more && (nodes.isEmpty() || next.isBlank() || next.length() > 4096 || next.equals(after))) {
            throw invalid();
        }
        return new Page<>(nodes.stream().map(mapper).toList(), next);
    }

    public record Discovery(String repository, String after) {}

    public record Page<T>(List<T> nodes, String next) {
        public Page {
            nodes = List.copyOf(nodes);
        }
    }

    public record Pull(int number, String state, Page<Review> reviews) {}

    public record Review(String id, String body, Instant submittedAt, String state, String url, Actor author) {}

    public record Actor(String id, String login, String type) {}
}
