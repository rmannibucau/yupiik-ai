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
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrictSolvePublisherTest {
    private static final String BASE = "a".repeat(40);
    private static final String TREE = "b".repeat(40);
    private static final String BLOB = "c".repeat(40);
    private static final String NEW_TREE = "d".repeat(40);
    private static final String COMMIT = "e".repeat(40);
    private static final String URL = "https://github.com/up/repo/pull/42";

    @TempDir
    Path home;

    @Test
    void publishesWithDurableIntentsAndPreservesExecutableModeAndAttempt() throws Exception {
        try (final var fixture = new Fixture()) {
            assertEquals(URL, fixture.publish());
            assertEquals(
                    List.of("blob-0Intent", "treeIntent", "commitIntent", "refIntent", "prIntent"), fixture.intents);
            assertEquals(5, fixture.posts.size());
            final var tree = fixture.posts.get(1);
            assertEquals(TREE, tree.get("base_tree"));
            final var entry = TaskStore.map(((List<?>) tree.get("tree")).getFirst());
            assertEquals("100755", entry.get("mode"));
            assertEquals("blob", entry.get("type"));
            assertEquals("run.sh", entry.get("path"));
            assertEquals(BLOB, entry.get("sha"));
            assertEquals(List.of(BASE), fixture.posts.get(2).get("parents"));
            assertEquals(
                    "refs/heads/github-bot/" + fixture.id, fixture.posts.get(3).get("ref"));
            assertEquals("bot:github-bot/" + fixture.id, fixture.posts.get(4).get("head"));
            assertEquals("main", fixture.posts.get(4).get("base"));
            assertEquals("repo", fixture.posts.get(4).get("head_repo"));
            assertEquals(Boolean.FALSE, fixture.posts.get(4).get("maintainer_can_modify"));
            final var saved = fixture.task();
            assertEquals("retained-attempt", saved.get("attemptDirectory"));
            assertEquals(fixture.result, saved.get("result"));
            assertEquals(URL, fixture.publication().get("prUrl"));
            assertEquals(URL, fixture.publish());
            assertEquals(5, fixture.posts.size(), "Completed publication cannot repeat any POST");
        }
    }

    @Test
    void uncertainPrIsReconciledAcrossRestartIncludingClosedPrWithoutAnotherPost() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.fail = "prIntent";
            assertThrows(IllegalStateException.class, fixture::publish);
            assertTrue(fixture.publication().containsKey("prIntent"));
            assertFalse(fixture.publication().containsKey("prUrl"));
            fixture.fail = null;
            fixture.listed = true;
            fixture.stale = true; // Reconciliation must work after the upstream advances.
            final var restarted = new StrictSolvePublisher(
                    fixture.github, new TaskStore(fixture.directory, fixture.json), fixture.json, "bot");
            assertEquals(URL, restarted.publish(fixture.task(), fixture.result));
            assertEquals(5, fixture.posts.size());
            assertTrue(fixture.gets.stream()
                    .anyMatch(path -> path.contains("state=all")
                            && path.contains("head=bot%3Agithub-bot%2F")
                            && path.contains("base=main")));
        }
    }

    @Test
    void uncertainPrWithoutVerifiedMatchNeverRepeatsPost() throws Exception {
        for (final var mismatch : List.of("absent", "headRepo", "headSha", "base", "author", "marker")) {
            try (final var fixture = new Fixture()) {
                fixture.fail = "prIntent";
                assertThrows(IllegalStateException.class, fixture::publish);
                fixture.fail = null;
                fixture.listed = !"absent".equals(mismatch);
                fixture.mismatch = mismatch;
                assertThrows(IllegalStateException.class, fixture::publish, mismatch);
                assertThrows(IllegalStateException.class, fixture::publish, mismatch);
                assertEquals(5, fixture.posts.size(), mismatch);
            }
        }
    }

    @Test
    void everyAmbiguousWriteStopsAndCannotBeReplayedAfterRestart() throws Exception {
        for (final var stage : List.of("blob-0Intent", "treeIntent", "commitIntent", "refIntent")) {
            try (final var fixture = new Fixture()) {
                fixture.fail = stage;
                assertThrows(IllegalStateException.class, fixture::publish);
                final int count = fixture.posts.size();
                assertTrue(fixture.publication().containsKey(stage));
                fixture.fail = null;
                assertThrows(
                        IllegalStateException.class,
                        () -> new StrictSolvePublisher(
                                        fixture.github,
                                        new TaskStore(fixture.directory, fixture.json),
                                        fixture.json,
                                        "bot")
                                .publish(fixture.task(), fixture.result));
                assertEquals(count, fixture.posts.size(), stage);
                assertEquals("retained-attempt", fixture.task().get("attemptDirectory"));
                assertEquals(fixture.result, fixture.task().get("result"));
            }
        }
    }

    @Test
    void changedForkUpstreamBaseOrResultCannotRebindIntent() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.fail = "blob-0Intent";
            assertThrows(IllegalStateException.class, fixture::publish);
            final int count = fixture.posts.size();
            assertThrows(
                    IllegalStateException.class,
                    () -> new StrictSolvePublisher(fixture.github, fixture.store, fixture.json, "other")
                            .publish(fixture.task(), fixture.result));
            final var changed = new LinkedHashMap<>(TaskStore.map(fixture.result.get("strictSolve")));
            changed.put("title", "Different result");
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.publisher.publish(fixture.task(), Map.of("strictSolve", changed)));
            fixture.store.transaction(state -> state.task(fixture.id).put("repository", "other/repo"));
            assertThrows(IllegalStateException.class, fixture::publish);
            fixture.store.transaction(state -> {
                state.task(fixture.id).put("repository", "up/repo");
                state.task(fixture.id).put("strictSolveBaseBranch", "next");
                return null;
            });
            assertThrows(IllegalStateException.class, fixture::publish);
            assertEquals(count, fixture.posts.size());
        }
    }

    @Test
    void staleBaseWrongForkAndIncompleteOrSymbolicBaseTreesCannotWrite() throws Exception {
        for (final var mode : List.of("stale", "fork", "truncated", "symlink", "submodule", "ancestor")) {
            try (final var fixture = new Fixture()) {
                fixture.stale = "stale".equals(mode);
                fixture.wrongFork = "fork".equals(mode);
                fixture.truncated = "truncated".equals(mode);
                fixture.baseMode = switch (mode) {
                    case "symlink" -> "120000";
                    case "submodule" -> "160000";
                    default -> "100755";
                };
                if ("ancestor".equals(mode)) {
                    fixture.result = result(List.of(change("run.sh/child", "", false)));
                }
                assertThrows(RuntimeException.class, fixture::publish, mode);
                assertTrue(fixture.posts.isEmpty(), mode);
            }
        }
    }

    @Test
    void untrustedBaseCannotOverridePersistedHostAuthority() throws Exception {
        try (final var fixture = new Fixture()) {
            final var solve = new LinkedHashMap<>(TaskStore.map(fixture.result.get("strictSolve")));
            solve.put("baseSha", "f".repeat(40));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.publisher.publish(fixture.task(), Map.of("strictSolve", solve)));
            final var caller = fixture.task();
            caller.put("strictSolveBaseSha", "f".repeat(40));
            assertThrows(IllegalStateException.class, () -> fixture.publisher.publish(caller, fixture.result));
            fixture.store.transaction(state -> state.task(fixture.id).remove("strictSolveBaseSha"));
            assertThrows(IllegalArgumentException.class, fixture::publish);
            assertTrue(fixture.posts.isEmpty());
        }
    }

    @Test
    void newFilesUseRegularModeAndTruncatedTreesDoNotFallBackToGuessedModes() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.result = result(List.of(change("new.txt", "", false)));
            assertEquals(URL, fixture.publish());
            final var entry = TaskStore.map(((List<?>) fixture.posts.get(1).get("tree")).getFirst());
            assertEquals("100644", entry.get("mode"));
        }
    }

    @Test
    void deletionEitherUsesExplicitNullOrFailsBeforeAnyWrite() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.result = result(List.of(change("run.sh", "", true)));
            final var probe = new GitHubClient.TreeRequest(
                    TREE, List.of(new GitHubClient.TreeEntry("run.sh", "100755", "blob", null)));
            if (fixture.json.toString(probe).contains("\"sha\":null")) {
                assertEquals(URL, fixture.publish());
                assertEquals(4, fixture.posts.size(), "Deleting a file never creates a blob");
                final var entry =
                        TaskStore.map(((List<?>) fixture.posts.getFirst().get("tree")).getFirst());
                assertTrue(entry.containsKey("sha"));
                assertNull(entry.get("sha"));
            } else {
                final var failure = assertThrows(IllegalStateException.class, fixture::publish);
                assertTrue(failure.getMessage().contains("serialize nulls"));
                assertTrue(fixture.posts.isEmpty());
            }
        }
    }

    @Test
    void rejectsUnsafePathsInvalidBase64FlagsModesAndSizeLimitsBeforeWriting() throws Exception {
        final var invalid = new ArrayList<List<Map<String, Object>>>();
        for (final var path : List.of(
                "../x",
                "/x",
                "a/../x",
                ".git/config",
                "a/.GIT/config",
                "a\\b",
                "a//b",
                "a/./b",
                "a/",
                "a\u0000b",
                "C:/x",
                "a.",
                "a ",
                ".git~1/x")) {
            invalid.add(List.of(change(path, "", false)));
        }
        invalid.add(List.of(change("a", "not base64", false)));
        invalid.add(List.of(change("a", "YQ", false))); // Non-canonical base64.
        invalid.add(List.of(change("a", "YQ==", true)));
        invalid.add(List.of(Map.of("path", "a", "content", "", "delete", "false")));
        invalid.add(List.of(Map.of("path", "a", "content", "", "delete", false, "mode", "120000")));
        invalid.add(List.of(change("a", "", false), change("a", "", false)));
        invalid.add(List.of(change("a", "", false), change("a/b", "", false)));
        invalid.add(List.of());
        invalid.add(IntStream.range(0, 101)
                .mapToObj(i -> change("f" + i, "", false))
                .toList());
        final var large = Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]);
        invalid.add(List.of(change("a", large, false)));
        final var maximum = Base64.getEncoder().encodeToString(new byte[1024 * 1024]);
        invalid.add(IntStream.range(0, 5)
                .mapToObj(i -> change("f" + i, maximum, false))
                .toList());
        try (final var fixture = new Fixture()) {
            for (final var changes : invalid) {
                fixture.result = result(changes);
                assertThrows(IllegalArgumentException.class, fixture::publish);
            }
            assertTrue(fixture.posts.isEmpty());
            assertTrue(fixture.gets.isEmpty());
        }
    }

    private static Map<String, Object> result(final List<Map<String, Object>> changes) {
        return Map.of(
                "strictSolve",
                Map.of("baseSha", BASE, "title", "Fix the issue", "body", "Summary", "changes", changes));
    }

    private static Map<String, Object> change(final String path, final String content, final boolean delete) {
        return Map.of("path", path, "content", content, "delete", delete);
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper wireJson =
                new JsonMapperImpl(List.of(new GitHubClient.GitHubValueCodec()), key -> Optional.empty());
        private final JsonMapper json = BotJsonMapper.create(List.of(), key -> Optional.empty());
        private final Path directory = home.resolve(UUID.randomUUID().toString());
        private final TaskStore store = new TaskStore(directory, json);
        private final String id = store.transaction(state -> state.enqueue(
                "test",
                Map.of(
                        "repository",
                        "up/repo",
                        "strictSolveBaseSha",
                        BASE,
                        "strictSolveBaseBranch",
                        "main",
                        "strictSolveForkOwner",
                        "bot",
                        "attemptDirectory",
                        "retained-attempt")));
        private final HttpServer server;
        private final GitHubClient github;
        private final StrictSolvePublisher publisher;
        private final List<Map<String, Object>> posts = new CopyOnWriteArrayList<>();
        private final List<String> intents = new CopyOnWriteArrayList<>();
        private final List<String> gets = new CopyOnWriteArrayList<>();
        private Map<String, Object> result = result(List.of(change("run.sh", "ZWNobyBvaw==", false)));
        private volatile String fail;
        private volatile String mismatch;
        private volatile boolean listed;
        private volatile boolean stale;
        private volatile boolean wrongFork;
        private volatile boolean truncated;
        private volatile String baseMode = "100755";

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                final Object response;
                int status = 200;
                if ("POST".equals(exchange.getRequestMethod())) {
                    final var payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    final var request = TaskStore.map(wireJson.fromString(GitHubClient.GitHubValue.class, payload)
                            .value());
                    posts.add(request);
                    final var stage = path.endsWith("/blobs")
                            ? "blob-0Intent"
                            : path.endsWith("/trees")
                                    ? "treeIntent"
                                    : path.endsWith("/commits")
                                            ? "commitIntent"
                                            : path.endsWith("/refs") ? "refIntent" : "prIntent";
                    // Read state from a separate transaction while the server handles the write.
                    // No matching durable intent means the request is refused, making success impossible.
                    if (!Boolean.TRUE.equals(publication().get(stage))) {
                        status = 409;
                    } else {
                        intents.add(stage);
                    }
                    if (stage.equals(fail)) {
                        status = 503;
                    }
                    response = switch (stage) {
                        case "blob-0Intent" -> Map.of("sha", BLOB);
                        case "treeIntent" -> Map.of("sha", NEW_TREE);
                        case "commitIntent" -> Map.of("sha", COMMIT);
                        case "refIntent" -> ref("github-bot/" + id, COMMIT);
                        default -> pr();
                    };
                } else {
                    gets.add(exchange.getRequestURI().toASCIIString());
                    if (path.equals("/user")) {
                        response = Map.of("login", "bot-author");
                    } else if (path.equals("/repos/bot/repo")) {
                        response = Map.of(
                                "fork",
                                true,
                                "full_name",
                                "bot/repo",
                                "parent",
                                Map.of("full_name", wrongFork ? "wrong/repo" : "up/repo"));
                    } else if (path.equals("/repos/up/repo")) {
                        response = Map.of("full_name", "up/repo", "default_branch", "main");
                    } else if (path.contains("/git/commits/")) {
                        response = Map.of("sha", BASE, "tree", Map.of("sha", TREE));
                    } else if (path.contains("/git/trees/")) {
                        response = Map.of(
                                "sha",
                                TREE,
                                "truncated",
                                truncated,
                                "tree",
                                List.of(Map.of("path", "run.sh", "mode", baseMode, "type", "blob", "sha", BLOB)));
                    } else if (path.equals("/repos/up/repo/git/ref/heads/main")) {
                        response = ref("main", stale ? "f".repeat(40) : BASE);
                    } else if (path.contains("/git/ref/heads/github-bot/")) {
                        response = ref("github-bot/" + id, COMMIT);
                    } else if (path.endsWith("/pulls")) {
                        response = listed ? List.of(pr()) : List.of();
                    } else {
                        status = 404;
                        response = Map.of();
                    }
                }
                final var wire = json.toString(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, wire.length);
                try (exchange) {
                    exchange.getResponseBody().write(wire);
                }
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "host-secret");
            publisher = new StrictSolvePublisher(github, store, json, "bot");
        }

        private Map<String, Object> pr() {
            return Map.of(
                    "number",
                    42,
                    "state",
                    "closed",
                    "html_url",
                    URL,
                    "body",
                    "Summary\n\n<!-- yca-task:" + ("marker".equals(mismatch) ? "other" : id) + " -->",
                    "user",
                    Map.of("login", "author".equals(mismatch) ? "other" : "bot-author"),
                    "head",
                    Map.of(
                            "ref",
                            "github-bot/" + id,
                            "sha",
                            "headSha".equals(mismatch) ? BASE : COMMIT,
                            "repo",
                            Map.of("full_name", "headRepo".equals(mismatch) ? "other/repo" : "bot/repo")),
                    "base",
                    Map.of("ref", "base".equals(mismatch) ? "next" : "main", "repo", Map.of("full_name", "up/repo")));
        }

        private Map<String, Object> ref(final String branch, final String sha) {
            return Map.of("ref", "refs/heads/" + branch, "object", Map.of("sha", sha));
        }

        private Map<String, Object> task() {
            return store.transaction(state -> new LinkedHashMap<>(state.task(id)));
        }

        private Map<String, Object> publication() {
            return TaskStore.map(task().get("strictSolvePublication"));
        }

        private String publish() {
            return publisher.publish(task(), result);
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
            wireJson.close();
        }
    }
}
