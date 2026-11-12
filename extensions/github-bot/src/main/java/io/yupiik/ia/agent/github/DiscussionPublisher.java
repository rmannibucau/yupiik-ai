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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A single comment publication with durable write intent, never an uncertain-write replay.
 * Reconciliation inspects at most 20 pages (1000 comments) in the exact destination thread.
 * An incomplete scan fails closed, including before the first write. No transport retries.
 */
public final class DiscussionPublisher {
    private static final List<String> BINDINGS = List.of(
            "sourceType", "repository", "number", "kind", "discussionId", "commentId", "replyToId", "reference");
    private final DiscussionClient discussions;
    private final TaskStore store;
    private final String login;

    public DiscussionPublisher(final GitHubClient github, final TaskStore store, final String login) {
        this.discussions = new DiscussionClient(github);
        this.store = store;
        this.login = login;
    }

    public String publish(final Map<String, Object> task, final String body) {
        final var id = required(task, "id");
        final var persisted = store.transaction(state -> Map.copyOf(state.task(id)));
        checkBindings(task, persisted);
        if (!"discussion".equals(persisted.get("sourceType"))
                || !Set.of("solve", "help").contains(persisted.get("kind"))) {
            throw new IllegalArgumentException("Only discussion solve/help publication is supported");
        }
        final var discussion = discussions.discussion(required(persisted, "discussionId"));
        if (!discussion.repository().equalsIgnoreCase(required(persisted, "repository"))
                || !String.valueOf(discussion.number()).equals(String.valueOf(persisted.get("number")))) {
            throw new IllegalArgumentException("Task discussion binding mismatch");
        }
        final var commentId = optional(persisted, "commentId");
        final var replyToId = optional(persisted, "replyToId");
        if (commentId == null) {
            if (replyToId != null || !discussion.url().equals(required(persisted, "reference"))) {
                throw new IllegalArgumentException("Root task destination mismatch");
            }
        } else {
            final var origin = discussions.comment(commentId);
            final var expectedThread = origin.replyToId() == null ? origin.id() : origin.replyToId();
            discussion.validate(origin, origin.replyToId());
            if (!expectedThread.equals(replyToId) || !origin.url().equals(required(persisted, "reference"))) {
                throw new IllegalArgumentException("Task comment/thread binding mismatch");
            }
            // Never trust a persisted thread ID without checking the actual parent node.
            final var parent = commentId.equals(replyToId) ? origin : discussions.comment(replyToId);
            discussion.validate(parent, null);
        }
        final var marker = "<!-- yca-task:" + id + ":discussion -->";
        if (persisted.containsKey("discussionPublishIntent")
                && !marker.equals(persisted.get("discussionPublishIntent"))) {
            throw new IllegalStateException("Invalid prior discussion intent");
        }
        String after = null;
        boolean complete = false;
        for (int pageNumber = 0; pageNumber < 20; pageNumber++) {
            final var page = replyToId == null
                    ? discussions.comments(discussion.id(), after)
                    : discussions.replies(replyToId, after);
            for (final var candidate : page.nodes()) {
                discussion.validate(candidate, replyToId);
                if (authored(candidate, marker)) {
                    remember(id, persisted, candidate.url());
                    return candidate.url();
                }
            }
            if (page.next() == null) {
                complete = true;
                break;
            }
            after = page.next();
        }
        if (!complete) {
            throw new IllegalStateException(
                    "Discussion reconciliation exceeds 1000 comments; operator reconciliation required");
        }
        if (persisted.containsKey("discussionPublishIntent")) {
            throw new IllegalStateException("Uncertain prior discussion publication; no replay permitted");
        }
        if (body == null || body.isBlank() || marker.length() + 1 + body.length() > 30000) {
            throw new IllegalArgumentException(
                    "Discussion comment must be nonblank and at most 30000 characters including marker");
        }
        // Atomic claim: concurrent publishers can reconcile, but only one may perform a mutation.
        store.transaction(state -> {
            final var current = state.task(id);
            checkBindings(persisted, current);
            if (current.containsKey("discussionPublishIntent")) {
                throw new IllegalStateException(
                        "Discussion publication already claimed; reconcile before another write");
            }
            current.put("discussionPublishIntent", marker);
            return null;
        });
        final var posted = discussions.addComment(discussion.id(), replyToId, marker + "\n" + body);
        discussion.validate(posted, replyToId);
        if (!authored(posted, marker)) {
            throw new IllegalStateException("Unverified discussion mutation result; reconcile without replay");
        }
        remember(id, persisted, posted.url());
        return posted.url();
    }

    private boolean authored(final DiscussionClient.Comment comment, final String marker) {
        return login != null
                && login.equalsIgnoreCase(comment.author().login())
                && Set.of("User", "Bot").contains(comment.author().type())
                && comment.body().contains(marker);
    }

    private void remember(final String id, final Map<String, Object> expected, final String url) {
        store.transaction(state -> {
            final var current = state.task(id);
            checkBindings(expected, current);
            current.put("discussionPublishIntent", "<!-- yca-task:" + id + ":discussion -->");
            current.put("discussionPublishedUrl", url);
            return null;
        });
    }

    private void checkBindings(final Map<String, Object> expected, final Map<String, Object> actual) {
        for (final var key : BINDINGS) {
            final var left = expected.get(key);
            final var right = actual.get(key);
            if (!("number".equals(key)
                    ? Objects.equals(String.valueOf(left), String.valueOf(right))
                    : Objects.equals(left, right))) {
                throw new IllegalArgumentException("Task publication binding changed: " + key);
            }
        }
    }

    private String required(final Map<String, Object> value, final String key) {
        final var result = optional(value, key);
        if (result == null) {
            throw new IllegalArgumentException("Missing task field: " + key);
        }
        return result;
    }

    private String optional(final Map<String, Object> value, final String key) {
        if (value.get(key) == null) {
            return null;
        }
        if (!(value.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Invalid task field: " + key);
        }
        return text;
    }
}
