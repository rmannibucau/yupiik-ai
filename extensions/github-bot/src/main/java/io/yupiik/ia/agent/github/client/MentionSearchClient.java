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
import static io.yupiik.ia.agent.github.client.GraphqlResponses.integer;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.invalid;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.object;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.repositoryName;
import static io.yupiik.ia.agent.github.client.GraphqlResponses.text;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Public candidate metadata only: callers must fetch actual feeds and reviews and authorize separately. */
public final class MentionSearchClient {
    private static final String ISSUE_QUERY = "query MentionCandidates($query:String!,$after:String) {"
            + " search(query:$query,type:ISSUE,first:100,after:$after) { issueCount nodes { __typename"
            + " ... on Issue { number createdAt repository { nameWithOwner } }"
            + " ... on PullRequest { number createdAt repository { nameWithOwner } }"
            + " } pageInfo { hasNextPage endCursor } } }";
    private static final String DISCUSSION_QUERY = "query MentionCandidates($query:String!,$after:String) {"
            + " search(query:$query,type:DISCUSSION,first:100,after:$after) { discussionCount nodes { __typename"
            + " ... on Discussion { number createdAt repository { nameWithOwner } }"
            + " } pageInfo { hasNextPage endCursor } } }";
    private final GitHubClient github;

    public MentionSearchClient(final GitHubClient github) {
        this.github = Objects.requireNonNull(github);
    }

    public enum Kind {
        ISSUE,
        DISCUSSION
    }

    public Page search(final String query, final Kind kind, final String after) {
        if (query == null || query.isBlank() || query.length() > 2048) {
            throw new IllegalArgumentException("Invalid mention search query");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Missing mention search kind");
        }
        cursor(after);
        final var variables = new LinkedHashMap<String, Object>();
        variables.put("query", query);
        variables.put("after", after);
        final var envelope = object(github.graphql(kind == Kind.ISSUE ? ISSUE_QUERY : DISCUSSION_QUERY, variables));
        final var connection = object(object(envelope.get("data")).get("search"));
        final var count = integer(connection.get(kind == Kind.ISSUE ? "issueCount" : "discussionCount"));
        if (count < 0 || !(connection.get("nodes") instanceof List<?> nodes) || nodes.size() > 100) {
            throw invalid();
        }
        // Search counts are not a stable snapshot across continuation requests or index updates.
        // In particular, an empty terminal page need not imply a zero total count.
        if (after == null && count < nodes.size()) {
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
        final var candidates = new ArrayList<Candidate>(nodes.size());
        for (final var value : nodes) {
            final var node = object(value);
            final var type = text(node.get("__typename"));
            if (!(kind == Kind.ISSUE
                    ? "Issue".equals(type) || "PullRequest".equals(type)
                    : "Discussion".equals(type))) {
                throw invalid();
            }
            final var number = integer(node.get("number"));
            final var repository = repositoryName(object(node.get("repository")).get("nameWithOwner"));
            if (number <= 0) {
                throw invalid();
            }
            final Instant createdAt;
            try {
                createdAt = Instant.parse(text(node.get("createdAt")));
            } catch (final DateTimeException e) {
                throw invalid();
            }
            candidates.add(new Candidate(repository, number, type, createdAt));
        }
        return new Page(candidates, next, count);
    }

    public record Candidate(String repository, int number, String type, Instant createdAt) {}

    public record Page(List<Candidate> nodes, String next, int count) {
        public Page {
            nodes = List.copyOf(nodes);
        }
    }
}
