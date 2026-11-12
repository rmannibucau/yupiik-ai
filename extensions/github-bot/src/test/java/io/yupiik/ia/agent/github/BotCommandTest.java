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
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotCommandTest {
    private static final PollCommand.Options DEFAULT_OPTIONS =
            new PollCommand$Options$FusionConfigurationFactory(unused -> Optional.empty()).get();

    @TempDir
    Path home;

    @Test
    void previewsMutationsWithoutChangingTasksReceiptsOrAudit() throws Exception {
        final var conf = new GithubBotConfiguration$FusionConfigurationFactory(
                        key -> "github.home".equals(key) ? Optional.of(home.toString()) : Optional.empty())
                .get();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            final String id = store.transaction(state -> {
                final var created = state.enqueue("preview-test", Map.of("kind", "help", "repository", "acme/repo"));
                state.task(created).put("state", "failed");
                state.task(created).put("completedAt", "2000-01-01T00:00:00Z");
                return created;
            });
            final var original = Files.readString(home.resolve("state.json"));
            for (final var command : List.of(
                    new RetryCommand(new RetryCommand.Options(id, true), conf, json),
                    new DeleteCommand(new DeleteCommand.Options(id, true), conf, json),
                    new PurgeCommand(new PurgeCommand.Options(7, true), conf, json),
                    new CreateCommand(
                            new CreateCommand.Options("acme/repo", 1, "help", "preview", true), conf, json))) {
                command.run();
                assertEquals(original, Files.readString(home.resolve("state.json")));
            }
            assertFalse(Files.exists(home.resolve("audit.jsonl")));
        }
    }

    @Test
    void listsOnlyTasksMatchingAllExactFilters() throws Exception {
        final var conf = new GithubBotConfiguration$FusionConfigurationFactory(
                        key -> "github.home".equals(key) ? Optional.of(home.toString()) : Optional.empty())
                .get();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            final String wanted = store.transaction(state -> {
                final var id = state.enqueue("wanted", Map.of("kind", "review", "repository", "acme/repo"));
                state.enqueue("different-kind", Map.of("kind", "help", "repository", "acme/repo"));
                state.enqueue("different-repo", Map.of("kind", "review", "repository", "other/repo"));
                final var failed =
                        state.enqueue("different-state", Map.of("kind", "review", "repository", "acme/repo"));
                state.task(failed).put("state", "failed");
                return id;
            });
            final var output = new ArrayList<String>();
            final var logger = Logger.getLogger(ListCommand.class.getName());
            final var handler = new Handler() {
                @Override
                public void publish(final LogRecord record) {
                    output.add(record.getMessage());
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
            logger.addHandler(handler);
            try {
                new ListCommand(new ListCommand.Options("pending", "acme/repo", "review"), conf, json).run();
            } finally {
                logger.removeHandler(handler);
            }
            final var selected = (List<?>) ((Map<?, ?>) json.fromString(Object.class, output.getFirst())).get("tasks");
            assertEquals(1, selected.size());
            assertEquals(wanted, ((Map<?, ?>) selected.getFirst()).get("id"));
        }
    }

    @Test
    void auditsOperatorRetryAndDeleteWithoutRequestText() throws Exception {
        final var conf = new GithubBotConfiguration$FusionConfigurationFactory(
                        key -> "github.home".equals(key) ? Optional.of(home.toString()) : Optional.empty())
                .get();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            final String id = store.transaction(state -> {
                final var created = state.enqueue("audit-test", Map.of("kind", "help", "request", "sensitive-request"));
                state.task(created).put("state", "failed");
                return created;
            });
            new RetryCommand(new RetryCommand.Options(id, false), conf, json).run();
            assertEquals("pending", store.transaction(state -> state.task(id).get("state")));
            new DeleteCommand(new DeleteCommand.Options(id, false), conf, json).run();
            final var audit = Files.readString(home.resolve("audit.jsonl"));
            assertTrue(audit.contains("manual-retried"));
            assertTrue(audit.contains("manual-deleted"));
            assertFalse(audit.contains("sensitive-request"));
            assertTrue(store.transaction(TaskStore.State::list).isEmpty());
        }
    }

    @Test
    void preventsRetryOfPublishedFailureAndDeletionOfRetainedAttempt() {
        final var values = Map.of("github.home", home.toString());
        final var conf =
                new GithubBotConfiguration$FusionConfigurationFactory(k -> Optional.ofNullable(values.get(k))).get();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            final var store = new TaskStore(home, json);
            final String id = store.transaction(state -> state.enqueue("test:retry", Map.of("kind", "help")));
            store.transaction(state -> {
                final var task = state.task(id);
                task.put("state", "failed");
                task.put("publicationIntent", "published");
                return null;
            });
            assertThrows(
                    IllegalStateException.class,
                    () -> new RetryCommand(new RetryCommand.Options(id, false), conf, json).run());
            store.transaction(state -> {
                final var task = state.task(id);
                task.remove("publicationIntent");
                task.put("reviewIntent", "ambiguous-review");
                return null;
            });
            assertThrows(
                    IllegalStateException.class,
                    () -> new RetryCommand(new RetryCommand.Options(id, false), conf, json).run());
            store.transaction(state -> {
                final var task = state.task(id);
                task.remove("reviewIntent");
                task.put("attemptDirectory", home.resolve("attempt").toString());
                return null;
            });
            assertThrows(
                    IllegalStateException.class,
                    () -> new DeleteCommand(new DeleteCommand.Options(id, false), conf, json).run());
            assertEquals("failed", store.transaction(state -> state.task(id).get("state")));
        }
    }

    @Test
    void pollUsesAppPemAuthenticationAndTypedRepositoryDiscovery() throws Exception {
        final var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        final var key = keys.generateKeyPair().getPrivate();
        final var keyFile = home.resolve("key.pem");
        Files.writeString(
                keyFile,
                "-----BEGIN " + "PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder().encodeToString(key.getEncoded()) + "\n-----END "
                        + "PRIVATE KEY-----\n");
        final var installations = new AtomicInteger();
        final var polls = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                final String body;
                if (path.equals("/app/installations/456/access_tokens")) {
                    installations.incrementAndGet();
                    body = json.toString(Map.of(
                            "token",
                            "installation-token",
                            "expires_at",
                            Instant.now().plusSeconds(3600).toString()));
                } else if (!"Bearer installation-token"
                        .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                } else if (path.equals("/orgs/acme/repos") || path.equals("/users/alice/repos")) {
                    body = json.toString(List.of(
                            Map.of("full_name", "acme/repo", "visibility", "internal"),
                            Map.of("full_name", "acme/excluded", "visibility", "internal"),
                            Map.of("full_name", "acme/public", "visibility", "public")));
                } else {
                    if (!path.startsWith("/repos/acme/repo/")) {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    polls.incrementAndGet();
                    body = "[]";
                }
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var values = Map.ofEntries(
                    Map.entry("github.home", home.toString()),
                    Map.entry(
                            "github.api",
                            "http://127.0.0.1:" + server.getAddress().getPort()),
                    Map.entry("github.auth.type", "app"),
                    Map.entry("github.auth.appId", "123"),
                    Map.entry("github.auth.installationId", "456"),
                    Map.entry("github.auth.privateKeyFile", keyFile.toString()),
                    Map.entry("github.authorization.userIds", "42"),
                    Map.entry("github.repositories.discovery", "org:acme,user:alice"),
                    Map.entry("github.repositories.includes", "acme/*"),
                    Map.entry("github.repositories.excludes", "*/excluded"),
                    Map.entry("github.repositories.visibility", "internal"));
            final var conf = new GithubBotConfiguration$FusionConfigurationFactory(
                            k -> Optional.ofNullable(values.get(k)))
                    .get();
            new PollCommand(new PollCommand.Options(DEFAULT_OPTIONS.poll()), conf, json).run();
            assertEquals(1, installations.get());
            assertEquals(3, polls.get());
            final var store = new TaskStore(home, json);
            final String failed = store.transaction(state -> {
                final var id = state.enqueue("notification-test", Map.of("repository", "acme/repo", "kind", "help"));
                state.task(id).put("state", "failed");
                return id;
            });
            final var sent = new AtomicInteger();
            final var notifications = new NotificationService(null, null) {
                @Override
                public void onTurnEnd(
                        final String session,
                        final String title,
                        final String model,
                        final String provider,
                        final boolean success,
                        final String body) {
                    assertEquals(failed, session);
                    assertFalse(success);
                    assertFalse(body.contains("token"));
                    sent.incrementAndGet();
                }
            };
            final var executor = new ExecutorConfiguration$FusionConfigurationFactory(unused -> Optional.empty()).get();
            final var worker = new WorkerCommand(
                    new WorkerCommand.Options(
                            "",
                            new WorkerCommand$Options$FusionConfigurationFactory(unused -> Optional.empty())
                                    .get()
                                    .worker()),
                    conf,
                    json,
                    executor,
                    notifications);
            worker.run();
            worker.run();
            assertEquals(1, sent.get());
            assertEquals(true, store.transaction(state -> state.task(failed).get("failureNotified")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void patFailsClosedWhenEnvironmentCredentialIsMissing() {
        final var values = Map.of(
                "github.home",
                home.toString(),
                "github.auth.tokenEnv",
                "YCA_TEST_MISSING_HOST_TOKEN_1972",
                "github.authorization.userIds",
                "42",
                "github.repositories.explicit",
                "acme/repo");
        final var conf =
                new GithubBotConfiguration$FusionConfigurationFactory(k -> Optional.ofNullable(values.get(k))).get();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            assertThrows(
                    IllegalStateException.class,
                    () -> new PollCommand(new PollCommand.Options(DEFAULT_OPTIONS.poll()), conf, json).run());
        }
    }

    @Test
    void rejectsIncompleteAppConfigurationAndNonPkcs8Pem() throws Exception {
        final var key = home.resolve("invalid.pem");
        Files.writeString(key, "not a PKCS#8 key");
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), ignored -> Optional.empty())) {
            final var missing = Map.of("github.auth.type", "app");
            final var incomplete = new GithubBotConfiguration$FusionConfigurationFactory(
                            k -> Optional.ofNullable(missing.get(k)))
                    .get();
            assertThrows(IllegalArgumentException.class, () -> new GithubRuntime(incomplete, json, Clock.systemUTC()));
            final var values = Map.of(
                    "github.auth.type",
                    "app",
                    "github.auth.appId",
                    "123",
                    "github.auth.installationId",
                    "456",
                    "github.auth.privateKeyFile",
                    key.toString());
            final var invalid = new GithubBotConfiguration$FusionConfigurationFactory(
                            k -> Optional.ofNullable(values.get(k)))
                    .get();
            assertThrows(IllegalArgumentException.class, () -> new GithubRuntime(invalid, json, Clock.systemUTC()));
        }
    }
}
