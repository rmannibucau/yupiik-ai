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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPublisherTest {
    private static final String PATCH =
            "@@ -10,3 +20,3 @@ method\n context\n-old\n+new\n tail\n\\ No newline at end of file\n"
                    + "@@ -50 +60 @@\n-before\n+after\n";
    private static final String URL = "https://github.com/owner/repo/pull/12#pullrequestreview-1";

    @TempDir
    Path home;

    private JsonMapperImpl json;
    private HttpServer server;
    private GitHubClient github;
    private TaskStore store;
    private Map<String, Object> task;
    private final List<Map<String, Object>> posts = new CopyOnWriteArrayList<>();
    private final AtomicInteger heads = new AtomicInteger();
    private volatile List<Map<String, Object>> reviews = List.of();
    private volatile String initialSha = "head";
    private volatile String finalSha = "head";
    private volatile int postStatus = 200;
    private volatile boolean intentAtPost;

    @BeforeEach
    void setUp() throws Exception {
        json = BotJsonMapper.create(List.of(), key -> Optional.empty());
        store = new TaskStore(BotTestConfiguration.home(home), json);
        final var id = store.transaction(state -> state.enqueue(
                "review:1", TaskFixtures.task(Map.of("repository", "owner/repo", "number", 12, "kind", "review"))));
        task = TaskFixtures.map(store.transaction(state -> state.task(id).document()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var path = exchange.getRequestURI().getPath();
            final Object response;
            int status = 200;
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.add(TaskStore.map(json.fromString(
                        Object.class, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
                intentAtPost = store.transaction(state -> (state.task(id).reviewIntent() != null));
                status = postStatus;
                response = Map.of("html_url", URL);
            } else if (path.endsWith("/reviews")) {
                response = reviews;
            } else if (path.endsWith("/files")) {
                response =
                        List.of(Map.of("filename", "src/App.java", "patch", PATCH), Map.of("filename", "binary.png"));
            } else if (path.equals("/repos/owner/repo/pulls/12")) {
                response = Map.of("head", Map.of("sha", heads.incrementAndGet() == 1 ? initialSha : finalSha));
            } else {
                status = 404;
                response = Map.of();
            }
            final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        github = new GitHubClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                json,
                () -> "test",
                BotTestConfiguration.CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (github != null) {
            github.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (json != null) {
            json.close();
        }
    }

    @Test
    void publishesNativeCommentReviewPinnedToValidatedHead() {
        final var comments = List.of(finding("LEFT", 11), finding("RIGHT", 21), finding("RIGHT", 60));
        assertEquals(URL, publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(comments))));
        assertEquals(2, heads.get());
        assertTrue(intentAtPost);
        assertEquals(1, posts.size());
        final var posted = posts.getFirst();
        assertEquals("head", posted.get("commit_id"));
        assertEquals("COMMENT", posted.get("event"));
        assertEquals(marker() + "\nFindings", posted.get("body"));
        final var actual = (List<?>) posted.get("comments");
        assertEquals(3, actual.size());
        for (int i = 0; i < comments.size(); i++) {
            final var comment = TaskStore.map(actual.get(i));
            assertEquals(comments.get(i).get("path"), comment.get("path"));
            assertEquals(comments.get(i).get("side"), comment.get("side"));
            assertEquals(((Number) comments.get(i).get("line")).intValue(), ((Number) comment.get("line")).intValue());
            assertEquals(marker() + "\nExplain the defect", comment.get("body"));
            assertTrue(MentionParser.parse(String.valueOf(comment.get("body")), "bot")
                    .isEmpty());
        }
    }

    @Test
    void mapsBothSidesContextAndMultipleHunks() {
        assertEquals(
                Set.of(
                        "a:LEFT:10",
                        "a:RIGHT:20",
                        "a:LEFT:11",
                        "a:RIGHT:21",
                        "a:LEFT:12",
                        "a:RIGHT:22",
                        "a:LEFT:50",
                        "a:RIGHT:60"),
                ReviewPublisher.locations("a", PATCH));
    }

    @Test
    void rejectsEntireReviewBeforePostWhenAnyFindingIsInvalid() {
        final var invalid = List.of(
                finding("RIGHT", 999),
                finding("RIGHT", 21.5),
                finding("LEFT", 21),
                Map.<String, Object>of("path", "binary.png", "side", "RIGHT", "line", 1, "body", "No patch"),
                Map.<String, Object>of("path", "../App.java", "side", "RIGHT", "line", 21, "body", "Wrong path"),
                Map.<String, Object>of("path", "src/App.java", "side", "RIGHT", "line", 21, "body", " "));
        for (final var finding : invalid) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> publisher()
                            .publish(
                                    TaskFixtures.task(task),
                                    TaskFixtures.result(result(List.of(finding("RIGHT", 21), finding)))));
        }
        assertTrue(posts.isEmpty());
        assertFalse(store.<Boolean>transaction(state -> (state.task(id()).reviewIntent() != null)));
    }

    @Test
    void rejectsUnboundedReviewPayloadsWithoutWritingIntent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher()
                        .publish(
                                TaskFixtures.task(task),
                                TaskFixtures.result(result(Collections.nCopies(31, finding("RIGHT", 21))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher()
                        .publish(
                                TaskFixtures.task(task),
                                TaskFixtures.result(
                                        Map.of("review", Map.of("headSha", "head", "body", "x".repeat(30001))))));
        final var oversizedFinding =
                Map.<String, Object>of("path", "src/App.java", "side", "RIGHT", "line", 21, "body", "x".repeat(10001));
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher()
                        .publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of(oversizedFinding)))));
        assertTrue(posts.isEmpty());
        assertFalse(store.<Boolean>transaction(state -> (state.task(id()).reviewIntent() != null)));
    }

    @Test
    void rejectsStaleHeadBeforePublication() {
        initialSha = "new-head";
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
        assertEquals(1, heads.get());
        assertTrue(posts.isEmpty());
    }

    @Test
    void rechecksHeadAfterDiffValidationBeforeWritingIntent() {
        finalSha = "changed-during-validation";
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher()
                        .publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of(finding("RIGHT", 21))))));
        assertEquals(2, heads.get());
        assertTrue(posts.isEmpty());
        assertFalse(store.<Boolean>transaction(state -> (state.task(id()).reviewIntent() != null)));
    }

    @Test
    void ambiguousWriteBlocksDuplicatesUntilMatchingNativeReviewIsVisible() {
        postStatus = 503;
        assertThrows(
                IllegalStateException.class,
                () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
        assertTrue(intentAtPost);
        assertEquals(1, posts.size());
        // A marker alone is not enough: author, commit and native review state must all match.
        reviews = List.of(
                review("intruder", "head", "COMMENTED"),
                review("bot", "old-head", "COMMENTED"),
                review("bot", "head", "PENDING"),
                review("bot", "head", "APPROVED"));
        assertThrows(
                IllegalStateException.class,
                () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
        assertEquals(1, posts.size());
        reviews = List.of(review("BoT", "head", "COMMENTED"));
        // Reopen storage as after a dispatcher restart; no structured review is needed for
        // reconciliation.
        assertEquals(
                URL,
                BotTestConfiguration.reviewPublisher(
                                github, new TaskStore(BotTestConfiguration.home(home), json), "bot")
                        .publish(TaskFixtures.task(task), TaskFixtures.result(Map.of())));
        assertEquals(1, posts.size());
    }

    @Test
    void reconcilesPersistedReviewCommitAfterHeadMovesAcrossRestart() {
        postStatus = 503;
        assertThrows(
                IllegalStateException.class,
                () -> publisher().publish(TaskFixtures.task(task), TaskFixtures.result(result(List.of()))));
        assertEquals("head", store.transaction(state -> state.task(id()).reviewIntentSha()));
        initialSha = "new-head";
        finalSha = "new-head";
        reviews = List.of(review("bot", "head", "COMMENTED"));
        assertEquals(
                URL,
                BotTestConfiguration.reviewPublisher(
                                github, new TaskStore(BotTestConfiguration.home(home), json), "bot")
                        .publish(TaskFixtures.task(task), TaskFixtures.result(Map.of())));
        assertEquals(1, posts.size());
    }

    private Map<String, Object> review(final String login, final String sha, final String state) {
        return Map.of(
                "body", marker(), "user", Map.of("login", login), "commit_id", sha, "state", state, "html_url", URL);
    }

    private Map<String, Object> finding(final String side, final Number line) {
        return Map.of("path", "src/App.java", "side", side, "line", line, "body", "Explain the defect");
    }

    private Map<String, Object> result(final List<Map<String, Object>> comments) {
        return Map.of("review", Map.of("headSha", "head", "body", "Findings", "comments", comments));
    }

    private String id() {
        return String.valueOf(task.get("id"));
    }

    private String marker() {
        return "<!-- yca-task:" + id() + ":review -->";
    }

    private ReviewPublisher publisher() {
        return BotTestConfiguration.reviewPublisher(github, store, "bot");
    }
}
