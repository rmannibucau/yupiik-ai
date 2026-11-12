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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewSummaryPollerTest {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @TempDir
    Path home;

    @Test
    void defaultOptOutAndFailClosedValidationNeverCallNetwork() throws Exception {
        try (final var fixture = new Fixture()) {
            final var defaults = fixture.conf(Map.of("pollReviews", false));
            assertFalse(defaults.poll().pollReviews());
            assertEquals(0, fixture.poll(defaults));
            assertThrows(IllegalStateException.class, () -> fixture.poll(fixture.conf(Map.of("userIds", ""))));
            assertThrows(IllegalArgumentException.class, () -> fixture.poll(fixture.conf(Map.of("pageBudget", 0))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ReviewSummaryPoller(
                                    fixture.conf(Map.of()).shared(),
                                    fixture.conf(Map.of()).poll(),
                                    fixture.store,
                                    fixture.github,
                                    fixed(NOW),
                                    null)
                            .poll(List.of("owner/repo/../other")));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void onlySubmittedAuthorizedHumanMentionsEnqueueAndReceiptsSurviveTaskDeletion() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12, "state", "closed"));
            final var missingTime = review(2);
            missingTime.remove("submitted_at");
            fixture.reviews.put(
                    12,
                    List.of(
                            review(1),
                            missingTime,
                            changed(3, "state", "PENDING"),
                            changed(4, "submitted_at", NOW.minusSeconds(301).toString()),
                            changed(5, "user", Map.of("id", 77, "login", "human", "type", "User")),
                            changed(6, "user", Map.of("id", 42, "login", "BoT", "type", "User")),
                            changed(7, "user", Map.of("id", 42, "login", "human", "type", "Bot")),
                            changed(8, "body", "No invocation"),
                            changed(9, "submitted_at", NOW.minusSeconds(300).toString()),
                            changed(10, "user", null),
                            changed(11, "state", "DRAFT"),
                            changed(12, "body", "<!-- yca-task:previous-task:review -->\n@bot review follow up")));
            assertEquals(2, fixture.poll(fixture.conf(Map.of("pageBudget", 20))));
            final var tasks = fixture.store.transaction(TaskStore.State::list);
            assertEquals(2, tasks.size());
            final var task = tasks.getFirst();
            assertEquals("owner/repo", task.get("repository"));
            assertEquals(12, ((Number) task.get("number")).intValue());
            assertEquals("review", task.get("kind"));
            assertEquals("check this", task.get("request"));
            assertEquals("https://github.com/owner/repo/pull/12#pullrequestreview-1", task.get("reference"));
            assertEquals("42", String.valueOf(task.get("actorId")));
            fixture.store.transaction(state -> {
                assertTrue(state.receipts().containsKey("owner/repo/reviews/1"));
                state.tasks().clear();
                return null;
            });
            // New store/client/poller instances exercise durable state, not an in-memory deduplication set.
            try (final var github = new GitHubClient(URI.create(fixture.api()), fixture.json, () -> "test")) {
                assertEquals(
                        0,
                        new ReviewSummaryPoller(
                                        fixture.conf(Map.of("pageBudget", 20)).shared(),
                                        fixture.conf(Map.of("pageBudget", 20)).poll(),
                                        new TaskStore(home, fixture.json),
                                        github,
                                        fixed(NOW),
                                        null)
                                .poll(List.of("owner/repo")));
            }
            assertTrue(fixture.store.transaction(TaskStore.State::list).isEmpty());
            assertTrue(fixture.requests.getFirst().contains("state=all&sort=updated&direction=desc"));
        }
    }

    @Test
    void auditsEnqueueOnceAcrossReceiptReplayAndOnlyVisibleDenials() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(
                    12,
                    List.of(
                            review(1),
                            changed(2, "user", Map.of("id", 77, "login", "human", "type", "User")),
                            changed(3, "body", "ordinary sensitive nonmention"),
                            changed(4, "body", "`@bot review hidden mention`")));
            final var conf = fixture.conf(Map.of("pageBudget", 20));
            final var audit = new BotAudit(home, fixture.json);
            // Poller must pass the same sink into its internally created review-summary poller.
            assertEquals(
                    1, new Poller(conf.shared(), conf.poll(), fixture.store, fixture.github, fixed(NOW), audit).poll());
            final var id =
                    fixture.store.transaction(TaskStore.State::list).getFirst().get("id");
            fixture.store.transaction(state -> {
                state.tasks().clear();
                return null;
            });
            assertEquals(
                    0,
                    new ReviewSummaryPoller(
                                    conf.shared(),
                                    conf.poll(),
                                    new TaskStore(home, fixture.json),
                                    fixture.github,
                                    fixed(NOW),
                                    audit)
                            .poll(List.of("owner/repo")));
            final var events = Files.readAllLines(home.resolve("audit.jsonl")).stream()
                    .map(line -> TaskStore.map(fixture.json.fromString(Object.class, line)))
                    .toList();
            final var enqueued = events.stream()
                    .filter(event -> "enqueued".equals(event.get("event")))
                    .toList();
            assertEquals(1, enqueued.size());
            assertEquals(id, enqueued.getFirst().get("id"));
            assertEquals("42", String.valueOf(enqueued.getFirst().get("actorId")));
            assertEquals("owner/repo", enqueued.getFirst().get("repository"));
            assertEquals(12, ((Number) enqueued.getFirst().get("number")).intValue());
            assertEquals(
                    Set.of("at", "event", "id", "actorId", "repository", "number"),
                    enqueued.getFirst().keySet());
            final var denied = events.stream()
                    .filter(event -> "auth-denied".equals(event.get("event")))
                    .toList();
            assertEquals(2, denied.size());
            for (final var event : denied) {
                assertEquals(Set.of("at", "event", "actorId", "repository", "number"), event.keySet());
                assertEquals("77", String.valueOf(event.get("actorId")));
            }
            assertEquals(3, events.size());
        }
    }

    @Test
    void resumesIndependentLargePullAndReviewBacklogsWithStrictPerEndpointBudget() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13), Map.of("number", 14));
            fixture.reviews.put(12, List.of(review(1), review(2), review(3), review(4)));
            fixture.reviews.put(13, List.of(changed(5, "html_url", reference(13, 5))));
            fixture.reviews.put(14, List.of(changed(6, "html_url", reference(14, 6))));
            final var conf = fixture.conf(Map.of("pageBudget", 1));
            assertEquals(1, fixture.poll(conf));
            assertEquals(2, fixture.requests.size());
            fixture.requests.clear();
            assertEquals(2, fixture.poll(conf));
            assertEquals(3, fixture.requests.size());
            fixture.requests.clear();
            assertEquals(2, fixture.poll(conf));
            assertEquals(4, fixture.requests.size());
            assertEquals(1, fixture.poll(conf));
            assertEquals(6, fixture.store.transaction(TaskStore.State::list).size());
            // A conservative new traversal finds entries moved to earlier offsets.
            fixture.pulls =
                    List.of(Map.of("number", 15), Map.of("number", 12), Map.of("number", 13), Map.of("number", 14));
            fixture.reviews.put(15, List.of(changed(7, "html_url", reference(15, 7))));
            fixture.reviews.put(12, List.of(review(8), review(1), review(2), review(3), review(4)));
            for (int i = 0; i < 10; i++) {
                fixture.poll(conf);
            }
            assertEquals(8, fixture.store.transaction(TaskStore.State::list).size());
            assertEquals(
                    NOW.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void revisitsPendingReviewsAfterSubmission() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(changed(1, "state", "PENDING")));
            assertEquals(0, fixture.poll(fixture.conf(Map.of())));
            fixture.reviews.put(12, List.of(review(1)));
            assertEquals(1, fixture.poll(fixture.conf(Map.of())));
        }
    }

    @Test
    void failuresDoNotLoseDiscoveryOrAdvanceFailedReviewPage() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12), Map.of("number", 13));
            fixture.reviews.put(12, List.of(review(1), review(2), review(3)));
            fixture.reviews.put(13, List.of(changed(4, "html_url", reference(13, 4))));
            final var conf = fixture.conf(Map.of());
            assertEquals(1, fixture.poll(conf));
            fixture.failReview.set(true);
            assertThrows(GitHubClient.HttpException.class, () -> fixture.poll(conf));
            fixture.failReview.set(false);
            assertEquals(2, fixture.poll(conf));
            assertEquals(1, fixture.poll(conf));
            assertEquals(4, fixture.store.transaction(TaskStore.State::list).size());
        }
    }

    @Test
    void initialSinceIsPersistedAcrossRestartsAndExplicitBackfillIsHonored() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(
                    12,
                    List.of(changed(1, "submitted_at", NOW.minusSeconds(3600).toString())));
            final var initial = NOW.minusSeconds(3600).toString();
            assertEquals(1, fixture.poll(fixture.conf(Map.of("since", initial))));
            fixture.reviews.put(
                    12,
                    List.of(changed(2, "submitted_at", NOW.minusSeconds(3600).toString())));
            assertEquals(
                    1,
                    new ReviewSummaryPoller(
                                    fixture.conf(Map.of("since", "now")).shared(),
                                    fixture.conf(Map.of("since", "now")).poll(),
                                    new TaskStore(home, fixture.json),
                                    fixture.github,
                                    fixed(NOW.plusSeconds(86400)),
                                    null)
                            .poll(List.of("owner/repo")));
            assertEquals(
                    initial, fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void nowIsCapturedOnceEvenWhenTheFirstTraversalHasNoPulls() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(0, fixture.poll(fixture.conf(Map.of())));
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(review(1)));
            assertEquals(
                    1,
                    new ReviewSummaryPoller(
                                    fixture.conf(Map.of()).shared(),
                                    fixture.conf(Map.of()).poll(),
                                    fixture.store,
                                    fixture.github,
                                    fixed(NOW.plusSeconds(86400)),
                                    null)
                            .poll(List.of("owner/repo")));
            assertEquals(
                    NOW.toString(),
                    fixture.store.transaction(state -> state.cursors().get("initialSince")));
        }
    }

    @Test
    void rejectsReferencesOutsideThePolledPullAndRepository() throws Exception {
        for (final var ref : List.of(
                "https://evil.example/owner/repo/pull/12#pullrequestreview-1",
                "http://github.com/owner/repo/pull/12#pullrequestreview-1",
                "https://github.com/other/repo/pull/12#pullrequestreview-1",
                "https://github.com/owner/repo/pull/13#pullrequestreview-1",
                "https://github.com/owner/repo/issues/12#pullrequestreview-1",
                "https://github.com/owner/repo/pull/12#pullrequestreview-2",
                "https://github.com/owner/repo/pull/12#issuecomment-1",
                "https://github.com/owner/repo/pull/12/extra#pullrequestreview-1")) {
            try (final var fixture = new Fixture()) {
                fixture.pulls = List.of(Map.of("number", 12));
                fixture.reviews.put(12, List.of(changed(1, "html_url", ref)));
                assertThrows(IllegalStateException.class, () -> fixture.poll(fixture.conf(Map.of())));
                assertTrue(fixture.store.transaction(TaskStore.State::list).isEmpty());
            }
        }
    }

    @Test
    void teamRestrictionUsesImmutableIdentityAndFailsClosed() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.pulls = List.of(Map.of("number", 12));
            fixture.reviews.put(12, List.of(review(1)));
            final var conf = fixture.conf(Map.of("teams", "owner/team"));
            fixture.membership = "pending";
            assertEquals(
                    0,
                    new ReviewSummaryPoller(
                                    conf.shared(),
                                    conf.poll(),
                                    fixture.store,
                                    fixture.github,
                                    fixed(NOW),
                                    new BotAudit(home, fixture.json))
                            .poll(List.of("owner/repo")));
            final var events = Files.readAllLines(home.resolve("audit.jsonl"));
            assertEquals(1, events.size());
            final var denied = TaskStore.map(fixture.json.fromString(Object.class, events.getFirst()));
            assertEquals("auth-denied", denied.get("event"));
            assertEquals(Set.of("at", "event", "actorId", "repository", "number"), denied.keySet());
            assertEquals("42", String.valueOf(denied.get("actorId")));
            fixture.membership = "active";
            fixture.resolvedLogin = "renamed";
            assertEquals(0, fixture.poll(conf));
            fixture.resolvedLogin = "human";
            fixture.failTeam.set(true);
            assertThrows(GitHubClient.HttpException.class, () -> fixture.poll(conf));
            assertTrue(fixture.store.transaction(TaskStore.State::list).isEmpty());
            fixture.failTeam.set(false);
            assertEquals(1, fixture.poll(conf));
            assertTrue(fixture.requests.contains("/user/42"));
            assertTrue(fixture.requests.contains("/orgs/owner/teams/team/memberships/human"));
        }
    }

    private static Clock fixed(final Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static String reference(final int number, final int id) {
        return "https://github.com/owner/repo/pull/" + number + "#pullrequestreview-" + id;
    }

    private static Map<String, Object> review(final int id) {
        return new LinkedHashMap<>(Map.of(
                "id",
                id,
                "state",
                "COMMENTED",
                "submitted_at",
                NOW.toString(),
                "user",
                Map.of("id", 42, "login", "human", "type", "User"),
                "body",
                "@bot review check this",
                "html_url",
                reference(12, id)));
    }

    private static Map<String, Object> changed(final int id, final String key, final Object value) {
        final var review = review(id);
        review.put(key, value);
        return review;
    }

    private record Settings(GithubBotConfiguration shared, PollOptions poll) {}

    private final class Fixture implements AutoCloseable {
        private final JsonMapperImpl json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), key -> Optional.empty());
        private final HttpServer server;
        private final GitHubClient github;
        private final TaskStore store = new TaskStore(home, json);
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final Map<Integer, List<Map<String, Object>>> reviews = new LinkedHashMap<>();
        private final AtomicBoolean failReview = new AtomicBoolean();
        private final AtomicBoolean failTeam = new AtomicBoolean();
        private List<Map<String, Object>> pulls = List.of();
        private String membership = "active";
        private String resolvedLogin = "human";

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
            github = new GitHubClient(URI.create(api()), json, () -> "test");
        }

        private String api() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int poll(final Settings conf) {
            return new ReviewSummaryPoller(conf.shared(), conf.poll(), store, github, fixed(NOW), null)
                    .poll(List.of("owner/repo"));
        }

        private Settings conf(final Map<String, Object> overrides) {
            final var values = new LinkedHashMap<String, String>();
            values.put("github.home", home.toString());
            values.put("github.api", api());
            values.put("github.login", "bot");
            values.put("github.authorization.userIds", "42");
            values.put("github.repositories.explicit", "owner/repo");
            values.put("poll.pageBudget", "1");
            values.put("poll.pollReviews", "true");
            overrides.forEach((key, value) -> values.put(
                    switch (key) {
                        case "userIds", "teams" -> "github.authorization." + key;
                        case "since", "pageBudget", "pollReviews" -> "poll." + key;
                        default -> throw new IllegalArgumentException("Unsupported setting: " + key);
                    },
                    value.toString()));
            return new Settings(
                    new GithubBotConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key)))
                            .get(),
                    new PollCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key)))
                            .get()
                            .poll());
        }

        private void respond(final HttpExchange exchange) throws IOException {
            final var uri = exchange.getRequestURI();
            final var path = uri.getPath();
            requests.add(uri.toString());
            Object body;
            int status = 200;
            if (path.equals("/user/42")) {
                body = Map.of("id", 42, "login", resolvedLogin);
            } else if (path.startsWith("/orgs/")) {
                status = failTeam.get() ? 403 : 200;
                body = Map.of("state", membership);
            } else {
                final List<?> items;
                if (path.endsWith("/reviews")) {
                    final var number = Integer.parseInt(path.split("/")[5]);
                    items = reviews.getOrDefault(number, List.of());
                    status = failReview.get() ? 403 : 200;
                } else {
                    items = pulls;
                }
                final var query = uri.getQuery();
                final int page = query != null && query.contains("&page=")
                        ? Integer.parseInt(query.substring(query.lastIndexOf("&page=") + 6))
                        : 1;
                final int offset = Math.min(page - 1, items.size());
                body = items.subList(offset, Math.min(offset + 1, items.size()));
                if (offset + 1 < items.size()) {
                    final var baseQuery = query.replaceAll("&page=[0-9]+", "");
                    exchange.getResponseHeaders()
                            .set(
                                    "Link",
                                    "<" + api() + path + "?" + baseQuery + "&page=" + (page + 1) + ">; rel=\"next\"");
                }
            }
            final var bytes = json.toString(body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
        }
    }
}
