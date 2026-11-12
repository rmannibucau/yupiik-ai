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

import io.yupiik.ia.agent.github.store.TaskStore;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Discussions domain API. Every call is one GraphQL request, with no local retry. */
public final class DiscussionClient {
    public static final int PAGE_SIZE = 50;
    private static final String AUTHOR = "author { __typename login ... on User { databaseId } }";
    private static final String DISCUSSION =
            "id number title body updatedAt url repository { nameWithOwner } " + AUTHOR;
    private static final String COMMENT =
            "id databaseId body updatedAt url " + AUTHOR + " discussion { id } replyTo { id }";
    private static final String PAGE = "pageInfo { hasNextPage endCursor }";
    private final GitHubClient github;

    public DiscussionClient(final GitHubClient github) {
        this.github = github;
    }

    public Page<Discussion> discussions(final String repository, final String after) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Expected owner/repository");
        }
        final var parts = repository.split("/");
        final var variables = variables(after);
        variables.put("owner", parts[0]);
        variables.put("name", parts[1]);
        final var data = execute(
                "query DiscussionDiscovery($owner:String!,$name:String!,$after:String) {"
                        + " repository(owner:$owner,name:$name) { discussions(first:50,after:$after,"
                        + "orderBy:{field:CREATED_AT,direction:ASC}) { nodes { " + DISCUSSION + " } " + PAGE + " } } }",
                variables);
        return page(object(data.get("repository")).get("discussions"), after, this::discussion);
    }

    public Discussion discussion(final String id) {
        final var node =
                node("query DiscussionRead($id:ID!) { node(id:$id) { ... on Discussion { " + DISCUSSION + " } } }", id);
        final var result = discussion(node);
        if (!id.equals(result.id())) {
            throw new IllegalStateException("Discussion node mismatch");
        }
        return result;
    }

    public Comment comment(final String id) {
        final var result = comment(node(
                "query DiscussionCommentRead($id:ID!) { node(id:$id) {" + " ... on DiscussionComment { " + COMMENT
                        + " } } }",
                id));
        if (!id.equals(result.id())) {
            throw new IllegalStateException("Comment node mismatch");
        }
        return result;
    }

    public Page<Comment> comments(final String discussionId, final String after) {
        return commentsPage(discussionId, after, false);
    }

    public Page<Comment> replies(final String commentId, final String after) {
        return commentsPage(commentId, after, true);
    }

    public Comment addComment(final String discussionId, final String replyToId, final String body) {
        final var input = new LinkedHashMap<String, Object>();
        input.put("discussionId", requireId(discussionId));
        if (replyToId != null) {
            input.put("replyToId", requireId(replyToId));
        }
        if (body == null || body.isBlank() || body.length() > 30000) {
            throw new IllegalArgumentException("Expected nonblank comment of at most 30000 characters");
        }
        input.put("body", body);
        return comment(object(execute(
                                "mutation DiscussionPublish($input:AddDiscussionCommentInput!) {"
                                        + " addDiscussionComment(input:$input) { comment { " + COMMENT + " } } }",
                                Map.of("input", input))
                        .get("addDiscussionComment"))
                .get("comment"));
    }

    private Page<Comment> commentsPage(final String id, final String after, final boolean replies) {
        final var variables = variables(after);
        variables.put("id", requireId(id));
        final var field = replies ? "replies" : "comments";
        final var data = execute(
                "query " + (replies ? "DiscussionReplies" : "DiscussionComments")
                        + "($id:ID!,$after:String) { node(id:$id) { ... on "
                        + (replies ? "DiscussionComment" : "Discussion") + " { id " + field
                        + "(first:50,after:$after) { nodes { " + COMMENT + " } " + PAGE + " } } } }",
                variables);
        final var node = object(data.get("node"));
        if (!id.equals(node.get("id"))) {
            throw new IllegalStateException("Connection parent mismatch");
        }
        return page(node.get(field), after, this::comment);
    }

    private Map<String, Object> node(final String query, final String id) {
        return object(execute(query, Map.of("id", requireId(id))).get("node"));
    }

    private Map<String, Object> execute(final String query, final Map<String, Object> variables) {
        final var response = object(github.graphql(query, variables));
        // Partial data plus errors must never advance cursors or authorize publication.
        if (response.containsKey("errors")
                && (!(response.get("errors") instanceof List<?> errors) || !errors.isEmpty())) {
            throw new IllegalStateException("GitHub GraphQL reported errors");
        }
        return object(response.get("data"));
    }

    private Discussion discussion(final Object raw) {
        final var value = object(raw);
        final var number = positive(value.get("number"));
        final var result = new Discussion(
                requireId(text(value, "id")),
                text(object(value.get("repository")), "nameWithOwner"),
                Integer.parseInt(number),
                text(value, "title"),
                text(value, "body"),
                Instant.parse(text(value, "updatedAt")),
                text(value, "url"),
                actor(value.get("author")));
        result.validateReference(result.url(), null);
        return result;
    }

    private Comment comment(final Object raw) {
        final var value = object(raw);
        return new Comment(
                requireId(text(value, "id")),
                positive(value.get("databaseId")),
                text(object(value.get("discussion")), "id"),
                value.get("replyTo") == null ? null : text(object(value.get("replyTo")), "id"),
                text(value, "body"),
                Instant.parse(text(value, "updatedAt")),
                text(value, "url"),
                actor(value.get("author")));
    }

    private Actor actor(final Object raw) {
        if (raw == null) {
            return new Actor("Deleted", "", null);
        }
        final var value = object(raw);
        final var type = text(value, "__typename");
        return new Actor(
                type,
                text(value, "login"),
                "User".equals(type) && value.get("databaseId") != null ? positive(value.get("databaseId")) : null);
    }

    private <T> Page<T> page(final Object raw, final String after, final Function<Object, T> mapper) {
        final var value = object(raw);
        if (!(value.get("nodes") instanceof List<?> nodes) || nodes.size() > PAGE_SIZE) {
            throw new IllegalStateException("Invalid GraphQL connection nodes");
        }
        final var info = object(value.get("pageInfo"));
        if (!(info.get("hasNextPage") instanceof Boolean more)) {
            throw new IllegalStateException("Missing GraphQL page information");
        }
        final var next = more ? text(info, "endCursor") : null;
        if (more && (next.isBlank() || next.equals(after) || nodes.isEmpty())) {
            throw new IllegalStateException("Non-progressing GraphQL cursor");
        }
        return new Page<>(nodes.stream().map(mapper).toList(), next);
    }

    private Map<String, Object> variables(final String after) {
        final var variables = new LinkedHashMap<String, Object>();
        if (after != null) {
            variables.put("after", after);
        }
        return variables;
    }

    private static String requireId(final String id) {
        if (id == null || id.isBlank() || id.length() > 512) {
            throw new IllegalArgumentException("Invalid GraphQL node identifier");
        }
        return id;
    }

    private static String positive(final Object value) {
        final var id = String.valueOf(value);
        if (!id.matches("[1-9][0-9]*")) {
            throw new IllegalStateException("Expected positive numeric GitHub identifier");
        }
        return id;
    }

    private static String text(final Map<String, Object> value, final String key) {
        if (!(value.get(key) instanceof String result)) {
            throw new IllegalStateException("Missing GraphQL field: " + key);
        }
        return result;
    }

    private static Map<String, Object> object(final Object value) {
        return TaskStore.map(value);
    }

    public record Actor(String type, String login, String databaseId) {}

    public record Page<T>(List<T> nodes, String next) {
        public Page {
            nodes = List.copyOf(nodes);
        }
    }

    public record Comment(
            String id,
            String databaseId,
            String discussionId,
            String replyToId,
            String body,
            Instant updatedAt,
            String url,
            Actor author) {}

    public record Discussion(
            String id,
            String repository,
            int number,
            String title,
            String body,
            Instant updatedAt,
            String url,
            Actor author) {
        /** Binds a comment's node relationship and canonical URL to this discussion and thread. */
        public void validate(final Comment comment, final String expectedReplyToId) {
            if (!id.equals(comment.discussionId()) || !Objects.equals(expectedReplyToId, comment.replyToId())) {
                throw new IllegalStateException("Comment belongs to a different discussion or thread");
            }
            validateReference(comment.url(), comment.databaseId());
        }

        public void validateReference(final String reference, final String databaseId) {
            final var root = URI.create(url);
            final var candidate = URI.create(reference);
            if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                    || !"https".equals(root.getScheme())
                    || root.getHost() == null
                    || root.getUserInfo() != null
                    || (root.getPort() != -1 && root.getPort() != 443)
                    || root.getRawQuery() != null
                    || root.getRawFragment() != null
                    || !("/" + repository + "/discussions/" + number).equals(root.getRawPath())
                    || !Objects.equals(root.getScheme(), candidate.getScheme())
                    || !Objects.equals(root.getRawAuthority(), candidate.getRawAuthority())
                    || !Objects.equals(root.getRawPath(), candidate.getRawPath())
                    || candidate.getRawQuery() != null
                    || !Objects.equals(
                            databaseId == null ? null : "discussioncomment-" + databaseId,
                            candidate.getRawFragment())) {
                throw new IllegalStateException("Unexpected discussion reference");
            }
        }
    }
}
