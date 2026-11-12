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
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand.Options;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PollerTest {
    @TempDir
    Path home;

    private GithubBotConfiguration configuration(final String api, final String ids) {
        return new GithubBotConfiguration(
                home.toString(),
                api,
                "bot",
                0,
                new GithubBotConfiguration.Auth(
                        GithubBotConfiguration.AuthType.pat, "GH_TOKEN", "", 0, "", "GH_AGENT_TOKEN"),
                new GithubBotConfiguration.Authorization(ids, ""),
                new GithubBotConfiguration.Repositories(
                        "owner/repo", "", "", "", List.of(RepositorySelector.Visibility.values()), false, false));
    }

    @Test
    void optionalPollersRunConcurrentlyOnVirtualThreads() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(List.of(), ignored -> Optional.empty());
                final var github = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "test")) {
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(conf, json);
            final var options = new Options(
                    new PollOptions("now", 1, new PollOptions.Reviews(true), new PollOptions.Discussions(true)));
            final var started = new CountDownLatch(2);
            final Runnable rendezvous = () -> {
                assertTrue(Thread.currentThread().isVirtual());
                started.countDown();
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS), "optional pollers did not overlap");
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            };
            final var reviews = new ReviewSummaryPoller(conf, options, store, github, Clock.systemUTC(), null, null) {
                @Override
                public int poll(final List<String> repositories) {
                    rendezvous.run();
                    return 2;
                }
            };
            final var discussions =
                    new DiscussionPoller(conf, options, store, github, Clock.systemUTC(), null, null, null) {
                        @Override
                        public int poll(final List<String> repositories) {
                            rendezvous.run();
                            return 3;
                        }
                    };
            final var poller = new Poller(
                    conf,
                    options,
                    store,
                    github,
                    Clock.systemUTC(),
                    null,
                    new TeamAuthorization(github, conf),
                    reviews,
                    discussions,
                    new RepositoryDiscovery(conf, github, Clock.systemUTC()));
            assertEquals(5, poller.poll());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authorizesImmutableIdsAndDeduplicatesAfterDeletion() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), key -> Optional.empty())) {
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                final var body = path.endsWith("issues/comments")
                        ? json.toString(List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "user",
                                        Map.of("id", 42, "login", "human", "type", "User"),
                                        "body",
                                        "@bot solve fix it",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12#issuecomment-1"),
                                Map.of(
                                        "id",
                                        2,
                                        "user",
                                        Map.of("id", 77, "login", "human", "type", "User"),
                                        "body",
                                        "@bot solve do not run",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12#issuecomment-2"),
                                Map.of(
                                        "id",
                                        3,
                                        "user",
                                        Map.of("id", 77, "login", "human", "type", "User"),
                                        "body",
                                        "ordinary sensitive nonmention"),
                                Map.of(
                                        "id",
                                        4,
                                        "user",
                                        Map.of("id", 77, "login", "human", "type", "User"),
                                        "body",
                                        "`@bot solve hidden mention`"),
                                Map.of(
                                        "id",
                                        5,
                                        "user",
                                        Map.of("id", 42, "login", "human", "type", "User"),
                                        "body",
                                        "<!-- yca-task:previous-task -->\nMention @bot review on a pull request",
                                        "html_url",
                                        "https://github.com/owner/repo/issues/12#issuecomment-5")))
                        : "[]";
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            try (final var github = new GitHubClient(URI.create(conf.api()), json, () -> "test")) {
                final var poller = BotTestConfiguration.poller(
                        conf,
                        new PollOptions("now", 1, new PollOptions.Reviews(false), new PollOptions.Discussions(false)),
                        store,
                        github,
                        Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC),
                        new BotAudit(BotTestConfiguration.home(home), json));
                assertEquals(1, poller.poll());
                assertEquals(
                        1,
                        store.transaction(state -> state.list().stream()
                                        .map(TaskFixtures::map)
                                        .toList())
                                .size());
                final var id = store.transaction(state ->
                                state.list().stream().map(TaskFixtures::map).toList())
                        .getFirst()
                        .get("id");
                store.transaction(state -> {
                    state.tasks().clear();
                    return null;
                });
                assertEquals(0, poller.poll());
                final var events = Files.readAllLines(home.resolve("audit.jsonl")).stream()
                        .map(line -> TaskStore.map(json.fromString(Object.class, line)))
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
                assertEquals(2, denied.size()); // One visible unauthorized mention per scan, not nonmentions.
                for (final var event : denied) {
                    assertEquals(Set.of("at", "event", "actorId", "repository"), event.keySet());
                    assertEquals("77", String.valueOf(event.get("actorId")));
                }
                assertEquals(3, events.size());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyAllowlistDeniesBeforeNetwork() {
        try (final var json = BotJsonMapper.create(
                        List.of(new BotAudit$AuditEntry$FusionJsonCodec()), key -> Optional.empty());
                final var github = new GitHubClient(URI.create("http://127.0.0.1:1"), json, () -> "test")) {
            assertThrows(
                    IllegalStateException.class,
                    () -> BotTestConfiguration.poller(
                                    configuration("http://127.0.0.1:1", ""),
                                    new PollOptions(
                                            "now",
                                            1,
                                            new PollOptions.Reviews(false),
                                            new PollOptions.Discussions(false)),
                                    new TaskStore(BotTestConfiguration.home(home), json),
                                    github,
                                    Clock.systemUTC(),
                                    null)
                            .poll());
        }
    }

    @Test
    void pageBudgetDoesNotAdvancePastBacklog() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), key -> Optional.empty())) {
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders()
                        .set("Link", "<http://127.0.0.1:" + server.getAddress().getPort() + "/page2>; rel=\"next\"");
                final var bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            try (final var github = new GitHubClient(URI.create(conf.api()), json, () -> "test")) {
                BotTestConfiguration.poller(
                                conf,
                                new PollOptions(
                                        "now", 1, new PollOptions.Reviews(false), new PollOptions.Discussions(false)),
                                store,
                                github,
                                Clock.systemUTC(),
                                null)
                        .poll();
            }
            assertTrue(store.<Boolean>transaction(state -> !state.cursors().containsKey("owner/repo/issues")));
            assertTrue(store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues:next")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesNextPagesAndReplaysEditsBeforeAdvancingWatermark() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var phase = new AtomicInteger();
        final var pages = new CopyOnWriteArrayList<String>();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), key -> Optional.empty())) {
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                pages.add(path);
                final var isIssues = path.equals("/repos/owner/repo/issues");
                final var body = isIssues && phase.get() == 2
                        ? json.toString(List.of(Map.of(
                                "id",
                                9,
                                "user",
                                Map.of("id", 42, "login", "human"),
                                "body",
                                "@bot solve edited earlier page",
                                "html_url",
                                "https://github.com/owner/repo/issues/12")))
                        : "[]";
                if (isIssues) {
                    exchange.getResponseHeaders()
                            .set(
                                    "Link",
                                    "<http://127.0.0.1:" + server.getAddress().getPort() + "/page2>; rel=\"next\"");
                }
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var first = Instant.parse("2026-11-11T12:00:00Z");
            try (final var github = new GitHubClient(URI.create(conf.api()), json, () -> "test")) {
                assertEquals(
                        0,
                        BotTestConfiguration.poller(
                                        conf,
                                        new PollOptions(
                                                "now",
                                                1,
                                                new PollOptions.Reviews(false),
                                                new PollOptions.Discussions(false)),
                                        store,
                                        github,
                                        Clock.fixed(first, ZoneOffset.UTC),
                                        null)
                                .poll());
                phase.set(1);
                assertEquals(
                        0,
                        BotTestConfiguration.poller(
                                        conf,
                                        new PollOptions(
                                                "now",
                                                1,
                                                new PollOptions.Reviews(false),
                                                new PollOptions.Discussions(false)),
                                        store,
                                        github,
                                        Clock.fixed(first.plusSeconds(600), ZoneOffset.UTC),
                                        null)
                                .poll());
                assertTrue(pages.contains("/page2"));
                assertFalse(store.<Boolean>transaction(state -> state.cursors().containsKey("owner/repo/issues")));
                phase.set(2);
                assertEquals(
                        1,
                        BotTestConfiguration.poller(
                                        conf,
                                        new PollOptions(
                                                "now",
                                                1,
                                                new PollOptions.Reviews(false),
                                                new PollOptions.Discussions(false)),
                                        store,
                                        github,
                                        Clock.fixed(first.plusSeconds(1200), ZoneOffset.UTC),
                                        null)
                                .poll());
                assertEquals(
                        first.toString(),
                        store.transaction(state -> state.cursors().get("initialSince")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesPollersWithoutBlockingWorkerTransactions() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var requests = new AtomicInteger();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var executor = Executors.newFixedThreadPool(3);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for worker transaction");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try (final var json =
                BotJsonMapper.create(List.of(new BotAudit$AuditEntry$FusionJsonCodec()), key -> Optional.empty())) {
            final var conf =
                    configuration("http://127.0.0.1:" + server.getAddress().getPort(), "42");
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            try (final var github = new GitHubClient(URI.create(conf.api()), json, () -> "test")) {
                final var first = executor.submit(() -> BotTestConfiguration.poller(
                                conf,
                                new PollOptions(
                                        "now", 1, new PollOptions.Reviews(false), new PollOptions.Discussions(false)),
                                store,
                                github,
                                Clock.systemUTC(),
                                null)
                        .poll());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final var second = executor.submit(() -> BotTestConfiguration.poller(
                                conf,
                                new PollOptions(
                                        "now", 1, new PollOptions.Reviews(false), new PollOptions.Discussions(false)),
                                store,
                                github,
                                Clock.systemUTC(),
                                null)
                        .poll());
                assertEquals(
                        "worker",
                        executor.submit(() -> store.transaction(state -> "worker"))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(1, requests.get());
                assertFalse(second.isDone());
                release.countDown();
                assertEquals(0, first.get(10, TimeUnit.SECONDS));
                assertEquals(0, second.get(10, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }
}
