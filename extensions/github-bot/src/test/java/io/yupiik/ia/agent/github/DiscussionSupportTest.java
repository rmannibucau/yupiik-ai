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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP transport, generated persistence/GraphQL codecs, no live GitHub calls. */
class DiscussionSupportTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final String ROOT = "https://github.com/owner/repo/discussions/12";

    @TempDir
    Path home;

    @Test
    void optOutAndInvalidAuthorizationDoNotCallNetwork() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(false, "42", "", 1));
            assertThrows(IllegalStateException.class, () -> fixture.poll(true, "", "", 1));
            assertThrows(IllegalStateException.class, () -> fixture.poll(true, "alice", "", 1));
            assertThrows(IllegalArgumentException.class, () -> fixture.poll(true, "42", "", 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.poller(true, "42", "", 1).poll(List.of("owner/repo/../other")));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void enqueuesRootCommentAndReplyWithDurableReceiptsAndTextualEvidence() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discussion.put("body", "@bot solve root request");
            fixture.addComment("C1", 1, null, "@bot help comment request");
            fixture.addComment("R1", 2, "C1", "@bot solve reply request");
            fixture.comments = connection(List.of(fixture.nodes.get("C1")), null);
            fixture.replies.put("C1", connection(List.of(fixture.nodes.get("R1")), null));
            assertEquals(3, fixture.poll(true, "42", "", 1));
            final var tasks = fixture.store.transaction(TaskStore.State::list);
            assertEquals(3, tasks.size());
            final var root = tasks.stream()
                    .filter(it -> !it.containsKey("commentId"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("discussion", root.get("sourceType"));
            assertEquals("D1", root.get("discussionId"));
            assertFalse(root.containsKey("replyToId"));
            assertEquals(ROOT, root.get("reference"));
            assertEquals("solve", root.get("kind"));
            assertEquals("root request", root.get("request"));
            for (final var task : tasks) {
                assertEquals("42", task.get("actorId"));
                assertTrue(String.valueOf(task.get("discussionContext")).contains("Topic"));
                assertTrue(String.valueOf(task.get("discussionContext")).contains("@bot solve root request"));
                if (task.containsKey("commentId")) {
                    assertEquals("C1", task.get("replyToId"));
                }
            }
            fixture.store.transaction(state -> {
                state.tasks().clear();
                return null;
            });
            fixture.store = new TaskStore(home, fixture.json);
            assertEquals(0, fixture.poll(true, "42", "", 1));
            assertEquals(
                    3,
                    fixture.store.<Integer>transaction(state -> state.receipts().size()));
            assertTrue(fixture.store.transaction(TaskStore.State::list).isEmpty());
            final var discovery = fixture.requests.getFirst();
            assertTrue(String.valueOf(discovery.get("query")).contains("field:CREATED_AT,direction:ASC"));
            assertTrue(String.valueOf(discovery.get("query")).contains("... on User { databaseId }"));
            assertEquals("owner", TaskStore.map(discovery.get("variables")).get("owner"));
        }
    }

    @Test
    void rejectsBotsSelfUnknownIdsReviewQuotedCodeAndMarkedOutput() throws Exception {
        try (final var fixture = new Fixture()) {
            final var items = new ArrayList<Map<String, Object>>();
            final var bodies = List.of(
                    "@bot review unsupported",
                    "> @bot solve quoted",
                    "`@bot help`",
                    "<!-- yca-task:old:discussion -->\n@bot solve output",
                    "ordinary text");
            int id = 1;
            for (final var body : bodies) {
                items.add(fixture.addComment("C" + id, id++, null, body));
            }
            items.add(changed(
                    fixture.addComment("bot", id++, null, "@bot solve x"), "author", actor("Bot", "robot", null)));
            items.add(changed(fixture.addComment("self", id++, null, "@bot help"), "author", actor("User", "BoT", 42)));
            items.add(changed(
                    fixture.addComment("stranger", id++, null, "@bot help"), "author", actor("User", "alice", 99)));
            items.add(changed(fixture.addComment("deleted", id, null, "@bot help"), "author", null));
            fixture.comments = connection(items, null);
            assertEquals(0, fixture.poll(true, "42", "", 1));
            assertTrue(fixture.store.transaction(TaskStore.State::list).isEmpty());
        }
    }

    @Test
    void immutableIdentityAndTeamMembershipAreBothRequired() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discussion.put("body", "@bot help");
            fixture.member = false;
            assertEquals(0, fixture.poll(true, "42", "org/team", 1));
            fixture.member = true;
            fixture.currentLogin = "reused-login";
            assertEquals(0, fixture.poll(true, "42", "org/team", 1));
            fixture.currentLogin = "alice";
            assertEquals(1, fixture.poll(true, "42", "org/team", 1));
            assertTrue(fixture.restPaths.contains("/user/42"));
            assertTrue(fixture.restPaths.contains("/orgs/org/teams/team/memberships/alice"));
        }
    }

    @Test
    void independentDurableCursorsResumeAllThreeConnectionsAndRestartCompletedScans() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discovery = connection(List.of(fixture.discussion), "d-next");
            fixture.addComment("C1", 1, null, "@bot help first");
            fixture.addComment("C2", 2, null, "@bot help second");
            fixture.addComment("R1", 3, "C1", "@bot solve first reply");
            fixture.addComment("R2", 4, "C1", "@bot solve second reply");
            fixture.comments = connection(List.of(fixture.nodes.get("C1")), "c-next");
            fixture.replies.put("C1", connection(List.of(fixture.nodes.get("R1")), "r-next"));
            fixture.pages.put("DiscussionDiscovery::d-next", connection(List.of(), null));
            fixture.pages.put("DiscussionComments:D1:c-next", connection(List.of(fixture.nodes.get("C2")), null));
            fixture.pages.put("DiscussionReplies:C1:r-next", connection(List.of(fixture.nodes.get("R2")), null));
            assertEquals(2, fixture.poll(true, "42", "", 1));
            final var cursors = fixture.store.transaction(state -> new LinkedHashMap<>(state.cursors()));
            assertEquals("d-next", cursors.get("owner/repo/discussion:discovery:next"));
            assertEquals("c-next", cursors.get("owner/repo/discussion:comments/D1:next"));
            assertEquals("r-next", cursors.get("owner/repo/discussion:replies/C1:next"));
            fixture.store = new TaskStore(home, fixture.json);
            assertEquals(2, fixture.poll(true, "42", "", 1));
            final var resumed = fixture.store.transaction(state -> new LinkedHashMap<>(state.cursors()));
            assertFalse(resumed.containsKey("owner/repo/discussion:discovery:next"));
            assertFalse(resumed.containsKey("owner/repo/discussion:comments/D1:next"));
            assertFalse(resumed.containsKey("owner/repo/discussion:replies/C1:next"));
            assertEquals(0, fixture.poll(true, "42", "", 1));
            assertEquals(
                    "d-next",
                    fixture.store.transaction(state -> state.cursors().get("owner/repo/discussion:discovery:next")));
            assertEquals(
                    4,
                    fixture.store.<Integer>transaction(state -> state.receipts().size()));
        }
    }

    @Test
    void errorsAndMalformedPagesNeverAdvanceContinuation() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discovery = connection(List.of(fixture.discussion), "next");
            assertEquals(0, fixture.poll(true, "42", "", 1));
            fixture.graphqlErrors = true;
            assertThrows(IllegalStateException.class, () -> fixture.poll(true, "42", "", 1));
            assertEquals(
                    "next",
                    fixture.store.transaction(state -> state.cursors().get("owner/repo/discussion:discovery:next")));
            fixture.graphqlErrors = false;
            fixture.pages.put("DiscussionDiscovery::next", connection(List.of(fixture.discussion), "next"));
            assertThrows(IllegalStateException.class, () -> fixture.poll(true, "42", "", 1));
            assertEquals(
                    "next",
                    fixture.store.transaction(state -> state.cursors().get("owner/repo/discussion:discovery:next")));
        }
    }

    @Test
    void initialSinceIsDurableAndOldCommentsStillHaveTheirRepliesScanned() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discussion.put("body", "@bot help old root");
            fixture.discussion.put("updatedAt", NOW.minusSeconds(301).toString());
            final var old = fixture.addComment("C1", 1, null, "@bot help old comment");
            old.put("updatedAt", NOW.minusSeconds(301).toString());
            fixture.comments = connection(List.of(old), null);
            fixture.addComment("R1", 2, "C1", "@bot solve recent reply");
            fixture.replies.put("C1", connection(List.of(fixture.nodes.get("R1")), null));
            assertEquals(1, fixture.poll(true, "42", "", 1));
            assertEquals(
                    NOW.toString(),
                    fixture.store.transaction(state -> state.cursors().get("discussion:initialSince")));
        }
    }

    @Test
    void publishesRootAndThreadCommentsOnlyAfterDurableIntent() throws Exception {
        try (final var fixture = new Fixture()) {
            final var root = fixture.task(null, null);
            assertEquals(ROOT + "#discussioncomment-90", fixture.publisher().publish(root, "Root answer"));
            assertTrue(fixture.intentAtMutation);
            assertEquals(1, fixture.mutations.get());
            var input = TaskStore.map(
                    TaskStore.map(fixture.lastMutation.get("variables")).get("input"));
            assertEquals("D1", input.get("discussionId"));
            assertFalse(input.containsKey("replyToId"));
            assertTrue(String.valueOf(input.get("body")).endsWith("\nRoot answer"));
            assertEquals(
                    ROOT + "#discussioncomment-90",
                    fixture.store.transaction(
                            state -> state.task(String.valueOf(root.get("id"))).get("discussionPublishedUrl")));
            fixture.addComment("C1", 1, null, "@bot help");
            fixture.addComment("R1", 2, "C1", "@bot solve");
            final var reply = fixture.task("R1", "C1");
            fixture.publisher().publish(reply, "Thread answer");
            input = TaskStore.map(
                    TaskStore.map(fixture.lastMutation.get("variables")).get("input"));
            assertEquals("C1", input.get("replyToId"));
            final var top = fixture.task("C1", "C1");
            fixture.publisher().publish(top, "Top-level invocation answer");
            input = TaskStore.map(
                    TaskStore.map(fixture.lastMutation.get("variables")).get("input"));
            assertEquals("C1", input.get("replyToId"));
        }
    }

    @Test
    void uncertainMutationIsNeverReplayedAndReconcilesActorPlusMarker() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = fixture.task(null, null);
            fixture.mutationFailure = true;
            assertThrows(RuntimeException.class, () -> fixture.publisher().publish(task, "Answer"));
            assertEquals(1, fixture.mutations.get());
            fixture.store = new TaskStore(home, fixture.json);
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            assertEquals(1, fixture.mutations.get());
            final var marker = "<!-- yca-task:" + task.get("id") + ":discussion -->";
            final var forged = fixture.addComment("forged", 91, null, marker + "\nAnswer");
            fixture.comments = connection(List.of(forged), null);
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            forged.put("author", actor("Bot", "bot", null));
            assertEquals(ROOT + "#discussioncomment-91", fixture.publisher().publish(task, "Answer"));
            assertEquals(1, fixture.mutations.get());
        }
    }

    @Test
    void rejectsTaskDestinationTamperingAndCrossDiscussionNodesBeforeMutation() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.addComment("C1", 1, null, "@bot help");
            fixture.addComment("R1", 2, "C1", "@bot help");
            final var task = fixture.task("R1", "C1");
            final var tampered = new LinkedHashMap<>(task);
            tampered.put("replyToId", "R1");
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.publisher().publish(tampered, "Answer"));
            fixture.nodes.get("R1").put("discussion", Map.of("id", "other"));
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            fixture.nodes.get("R1").put("discussion", Map.of("id", "D1"));
            fixture.nodes.get("R1").put("url", "https://evil.test/owner/repo/discussions/12#discussioncomment-2");
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            assertEquals(0, fixture.mutations.get());
        }
    }

    @Test
    void publisherFailsClosedOnIncompleteReconciliationAndUnverifiedMutationResult() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = fixture.task(null, null);
            final var unrelated = fixture.addComment("C1", 1, null, "No marker");
            fixture.comments = connection(List.of(unrelated), "p1");
            for (int page = 1; page < 20; page++) {
                fixture.pages.put("DiscussionComments:D1:p" + page, connection(List.of(unrelated), "p" + (page + 1)));
            }
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            assertEquals(0, fixture.mutations.get());
            fixture.comments = connection(List.of(), null);
            fixture.badMutationUrl = true;
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            assertEquals(1, fixture.mutations.get());
            assertThrows(IllegalStateException.class, () -> fixture.publisher().publish(task, "Answer"));
            assertEquals(1, fixture.mutations.get());
        }
    }

    @Test
    void pollCapsEachConnectionAtTwentyPagesEvenWithLargerConfiguredBudget() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.discovery = connection(List.of(fixture.discussion), "d1");
            final var comment = fixture.addComment("C1", 1, null, "No invocation");
            final var reply = fixture.addComment("R1", 2, "C1", "No invocation");
            fixture.comments = connection(List.of(comment), "c1");
            fixture.replies.put("C1", connection(List.of(reply), "r1"));
            for (int page = 1; page < 20; page++) {
                fixture.pages.put(
                        "DiscussionDiscovery::d" + page, connection(List.of(fixture.discussion), "d" + (page + 1)));
                fixture.pages.put("DiscussionComments:D1:c" + page, connection(List.of(comment), "c" + (page + 1)));
                fixture.pages.put("DiscussionReplies:C1:r" + page, connection(List.of(reply), "r" + (page + 1)));
            }
            assertEquals(0, fixture.poll(true, "42", "", 100));
            for (final var operation : List.of("DiscussionDiscovery", "DiscussionComments", "DiscussionReplies")) {
                assertEquals(
                        20L,
                        fixture.requests.stream()
                                .filter(request ->
                                        String.valueOf(request.get("query")).startsWith("query " + operation + "("))
                                .count());
            }
            final var cursors = fixture.store.transaction(state -> new LinkedHashMap<>(state.cursors()));
            assertEquals("d20", cursors.get("owner/repo/discussion:discovery:next"));
            assertEquals("c20", cursors.get("owner/repo/discussion:comments/D1:next"));
            assertEquals("r20", cursors.get("owner/repo/discussion:replies/C1:next"));
        }
    }

    @Test
    void publicationRejectsUnsupportedKindsAndOversizeBodiesWithoutAnIntent() throws Exception {
        try (final var fixture = new Fixture()) {
            final var task = fixture.task(null, null);
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.publisher().publish(task, " "));
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.publisher().publish(task, "x".repeat(30000)));
            fixture.store.transaction(state -> {
                state.task(String.valueOf(task.get("id"))).put("kind", "review");
                return null;
            });
            final var review =
                    fixture.store.transaction(state -> new LinkedHashMap<>(state.task(String.valueOf(task.get("id")))));
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.publisher().publish(review, "Review"));
            assertEquals(0, fixture.mutations.get());
            assertFalse(fixture.store.<Boolean>transaction(
                    state -> state.task(String.valueOf(task.get("id"))).containsKey("discussionPublishIntent")));
        }
    }

    private static Map<String, Object> actor(final String type, final String login, final Integer id) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("__typename", type);
        result.put("login", login);
        if (id != null) {
            result.put("databaseId", id);
        }
        return result;
    }

    private static Map<String, Object> changed(final Map<String, Object> source, final String key, final Object value) {
        final var result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }

    private static Map<String, Object> connection(final List<?> nodes, final String next) {
        final var info = new LinkedHashMap<String, Object>();
        info.put("hasNextPage", next != null);
        info.put("endCursor", next);
        return Map.of("nodes", nodes, "pageInfo", info);
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapperImpl json = BotJsonMapper.create(List.of(), key -> Optional.empty());
        private TaskStore store = new TaskStore(home, json);
        private final HttpServer server;
        private final GitHubClient github;
        private final Map<String, Object> discussion = new LinkedHashMap<>(Map.of(
                "id",
                "D1",
                "number",
                12,
                "title",
                "Topic",
                "body",
                "No invocation",
                "updatedAt",
                NOW.toString(),
                "url",
                ROOT,
                "repository",
                Map.of("nameWithOwner", "owner/repo"),
                "author",
                actor("User", "alice", 42)));
        private final Map<String, Map<String, Object>> nodes = new ConcurrentHashMap<>();
        private volatile Map<String, Object> discovery = connection(List.of(discussion), null);
        private volatile Map<String, Object> comments = connection(List.of(), null);
        private final Map<String, Map<String, Object>> replies = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> pages = new ConcurrentHashMap<>();
        private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        private final List<String> restPaths = new CopyOnWriteArrayList<>();
        private final AtomicInteger mutations = new AtomicInteger();
        private volatile boolean member = true;
        private volatile String currentLogin = "alice";
        private volatile boolean graphqlErrors;
        private volatile boolean mutationFailure;
        private volatile boolean badMutationUrl;
        private volatile boolean intentAtMutation;
        private volatile Map<String, Object> lastMutation;

        private Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                Object response;
                int status = 200;
                if ("POST".equals(exchange.getRequestMethod())
                        && "/graphql".equals(exchange.getRequestURI().getPath())) {
                    final var request = TaskStore.map(json.fromString(
                            Object.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    requests.add(request);
                    final var query = String.valueOf(request.get("query"));
                    final var variables = TaskStore.map(request.get("variables"));
                    final var nodeId = String.valueOf(variables.getOrDefault("id", ""));
                    final var after = String.valueOf(variables.getOrDefault("after", ""));
                    final Object data;
                    if (query.startsWith("mutation")) {
                        mutations.incrementAndGet();
                        lastMutation = request;
                        final var input = TaskStore.map(variables.get("input"));
                        final var body = String.valueOf(input.get("body"));
                        intentAtMutation = store.transaction(state -> state.list().stream()
                                .anyMatch(
                                        task -> body.startsWith(String.valueOf(task.get("discussionPublishIntent")))));
                        final var posted = addComment("posted", 90, (String) input.get("replyToId"), body);
                        posted.put("author", actor("Bot", "bot", null));
                        if (badMutationUrl) {
                            posted.put("url", "https://evil.test/answer");
                        }
                        status = mutationFailure ? 500 : 200;
                        data = Map.of("addDiscussionComment", Map.of("comment", posted));
                    } else if (query.startsWith("query DiscussionDiscovery")) {
                        data = Map.of(
                                "repository",
                                Map.of("discussions", pages.getOrDefault("DiscussionDiscovery::" + after, discovery)));
                    } else if (query.startsWith("query DiscussionComments")) {
                        data = Map.of(
                                "node",
                                Map.of(
                                        "id",
                                        nodeId,
                                        "comments",
                                        pages.getOrDefault("DiscussionComments:" + nodeId + ":" + after, comments)));
                    } else if (query.startsWith("query DiscussionReplies")) {
                        data = Map.of(
                                "node",
                                Map.of(
                                        "id",
                                        nodeId,
                                        "replies",
                                        pages.getOrDefault(
                                                "DiscussionReplies:" + nodeId + ":" + after,
                                                replies.getOrDefault(nodeId, connection(List.of(), null)))));
                    } else if (query.startsWith("query DiscussionCommentRead")) {
                        data = Map.of("node", nodes.getOrDefault(nodeId, Map.of()));
                    } else if (query.startsWith("query DiscussionRead")) {
                        data = Map.of("node", discussion);
                    } else {
                        status = 400;
                        data = Map.of();
                    }
                    response = graphqlErrors
                            ? Map.of("data", data, "errors", List.of(Map.of("message", "denied")))
                            : Map.of("data", data);
                } else {
                    final var path = exchange.getRequestURI().getPath();
                    restPaths.add(path);
                    if (path.equals("/user/42")) {
                        response = Map.of("id", 42, "login", currentLogin);
                    } else if (path.equals("/orgs/org/teams/team/memberships/alice")) {
                        response = Map.of("state", member ? "active" : "pending");
                    } else {
                        status = 404;
                        response = Map.of();
                    }
                }
                final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            github = new GitHubClient(URI.create(api()), json, () -> "test");
        }

        private String api() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private Map<String, Object> addComment(
                final String id, final int databaseId, final String parent, final String body) {
            final var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("databaseId", databaseId);
            result.put("discussion", Map.of("id", "D1"));
            result.put("replyTo", parent == null ? null : Map.of("id", parent));
            result.put("body", body);
            result.put("updatedAt", NOW.toString());
            result.put("url", ROOT + "#discussioncomment-" + databaseId);
            result.put("author", actor("User", "alice", 42));
            nodes.put(id, result);
            return result;
        }

        private DiscussionPoller poller(final boolean enabled, final String ids, final String teams, final int budget) {
            final var conf = new GithubBotConfiguration(
                    home.toString(), api(), "bot", 0, null, new GithubBotConfiguration.Authorization(ids, teams), null);
            // Parent integration appends the default-false pollDiscussions option after pollReviews.
            return new DiscussionPoller(
                    conf,
                    new PollOptions("now", budget, false, enabled),
                    store,
                    github,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    null);
        }

        private int poll(final boolean enabled, final String ids, final String teams, final int budget) {
            return poller(enabled, ids, teams, budget).poll(List.of("owner/repo"));
        }

        private Map<String, Object> task(final String commentId, final String replyToId) {
            final var value = new LinkedHashMap<String, Object>();
            value.put("sourceType", "discussion");
            value.put("repository", "owner/repo");
            value.put("number", 12);
            value.put("kind", "help");
            value.put("discussionId", "D1");
            value.put(
                    "reference", commentId == null ? ROOT : nodes.get(commentId).get("url"));
            if (commentId != null) {
                value.put("commentId", commentId);
                value.put("replyToId", replyToId);
            }
            final var id = store.transaction(
                    state -> state.enqueue("test:" + state.tasks().size(), value));
            return store.transaction(state -> new LinkedHashMap<>(state.task(id)));
        }

        private DiscussionPublisher publisher() {
            return new DiscussionPublisher(github, store, "bot");
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
        }
    }
}
