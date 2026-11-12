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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** Polling never calls a model. Identity checks precede durable enqueue. */
@ApplicationScoped
public class Poller {

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final GithubBotConfiguration conf;
    private final PollOptions options;
    private final TaskStore store;
    private final GitHubClient github;
    private final Clock clock;
    private final BotAudit audit;
    private final TeamAuthorization authorization;
    private final ReviewSummaryPoller reviews;
    private final DiscussionPoller discussions;
    private final RepositoryDiscovery discovery;

    /** The optional audit sink is shared with the review-summary poller. */
    public Poller(
            final GithubBotConfiguration conf,
            final PollCommand.Options commandOptions,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit,
            final TeamAuthorization authorization,
            final ReviewSummaryPoller reviews,
            final DiscussionPoller discussions,
            final RepositoryDiscovery discovery) {
        this.conf = conf;
        this.options = commandOptions == null ? null : commandOptions.poll();
        this.store = store;
        this.github = github;
        this.clock = clock;
        this.audit = audit;
        this.authorization = authorization;
        this.reviews = reviews;
        this.discussions = discussions;
        this.discovery = discovery;
    }

    public int poll() {
        if (options.pageBudget() <= 0) {
            throw new IllegalArgumentException("Positive page budget required");
        }
        store.initialize();
        final var path = Path.of(conf.home()).toAbsolutePath().normalize().resolve("poll.lock");
        final var local = LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        local.lock();
        try (final var channel = FileChannel.open(
                        path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final var lock = channel.lock()) {
            return scan();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private List<String> repositories() {
        return discovery.repositories();
    }

    private int scan() {
        final Set<String> allowed = Arrays.stream(conf.authorization().userIds().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            throw new IllegalStateException("No authorized immutable user IDs configured; polling denied");
        }
        final var batch = UUID.randomUUID().toString();
        int created = 0;
        final var selected = repositories();
        for (final var repo : selected) {
            for (final var endpoint : List.of("issues", "issues/comments", "pulls/comments")) {
                final var key = repo + "/" + endpoint;
                final var start = clock.instant();
                final var cursors = store.transaction(state -> {
                    // Persist 'now' once, not once per restarted scan or endpoint.
                    final var initial = state.cursors()
                            .computeIfAbsent(
                                    "initialSince",
                                    ignored -> "now".equals(options.since())
                                            ? start.toString()
                                            : Instant.parse(options.since()).toString());
                    final var snapshot = new LinkedHashMap<>(state.cursors());
                    snapshot.putIfAbsent(key, initial);
                    return snapshot;
                });
                final var since =
                        Instant.parse(String.valueOf(cursors.get(key))).minusSeconds(300);
                final var savedNext = cursors.get(key + ":next");
                final var scanStart = String.valueOf(cursors.getOrDefault(key + ":scanStart", start.toString()));
                String next = savedNext == null
                        ? "/repos/" + repo + "/" + endpoint + "?per_page=100&sort=updated&direction=asc&since="
                                + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)
                                + (endpoint.equals("issues") ? "&state=all" : "")
                        : String.valueOf(cursors.getOrDefault(key + ":overlap", savedNext));
                // Replay the preceding page in addition to the budget, covering page-boundary shifts.
                final int budget = options.pageBudget() + (cursors.containsKey(key + ":overlap") ? 1 : 0);
                String overlap = next;
                int pages = 0;
                while (next != null && pages++ < budget) {
                    overlap = next;
                    final var page = github.page(next);
                    if (!(page.body() instanceof List<?> items)) {
                        throw new IllegalStateException("Expected GitHub list");
                    }
                    for (final var raw : items) {
                        final var item = TaskStore.map(raw);
                        final var user = item.get("user") instanceof Map<?, ?>
                                ? TaskStore.map(item.get("user"))
                                : Map.<String, Object>of();
                        final var parsed =
                                MentionParser.parse(String.valueOf(item.getOrDefault("body", "")), conf.login());
                        if (parsed.isEmpty()) {
                            continue;
                        }
                        if (!allowed.contains(String.valueOf(user.get("id")))
                                || conf.login().equalsIgnoreCase(String.valueOf(user.get("login")))
                                || "Bot".equals(user.get("type"))
                                || !authorization.allows(user.get("id"), String.valueOf(user.get("login")))) {
                            if (audit != null) {
                                final var identifiers = MutableTask.create();
                                identifiers.repository(repo);
                                if (user.get("id") != null) {
                                    identifiers.actorId(String.valueOf(user.get("id")));
                                }
                                audit.record("auth-denied", identifiers.document());
                            }
                            continue;
                        }
                        final var invocation = parsed.orElseThrow();
                        final var ref = String.valueOf(item.get("html_url"));
                        final var uri = URI.create(ref);
                        final var parts = uri.getPath().split("/");
                        if (parts.length < 5 || !(parts[3].equals("issues") || parts[3].equals("pull"))) {
                            throw new IllegalStateException("Unexpected event reference");
                        }
                        final var number = Integer.parseInt(parts[4]);
                        final var task = MutableTask.create();
                        task.repository(repo);
                        task.number((long) number);
                        task.reference(ref);
                        task.kind(TaskKind.valueOf(invocation.kind()));
                        task.request(invocation.request());
                        task.actorId(String.valueOf(user.get("id")));
                        task.batchId(batch);
                        task.createdAt(clock.instant().toString());
                        final var receipt = key + ":" + item.get("id");
                        final var id = store.transaction(state -> {
                            if (state.receipts().containsKey(receipt)) {
                                return null;
                            }
                            return state.enqueue(receipt, task.document());
                        });
                        if (id != null) {
                            created++;
                            if (audit != null) {
                                task.id(id);
                                audit.record("enqueued", task.document());
                            }
                        }
                    }
                    next = page.next();
                }
                // A capped/failed scan must retain its completed watermark. Receipts absorb replay.
                if (next == null) {
                    store.transaction(state -> {
                        final var old = Instant.parse(
                                String.valueOf(state.cursors().getOrDefault(key, Instant.EPOCH.toString())));
                        final var completed = Instant.parse(scanStart);
                        // Offset pagination is mutable. A resumed traversal cannot prove that earlier
                        // pages did not shrink; retain the lower bound and replay on the next traversal.
                        if (savedNext == null && completed.isAfter(old)) {
                            state.cursors().put(key, completed.toString());
                        }
                        state.cursors().remove(key + ":next");
                        state.cursors().remove(key + ":scanStart");
                        state.cursors().remove(key + ":overlap");
                        return null;
                    });
                } else {
                    final var continuation = next;
                    final var replay = overlap;
                    store.transaction(state -> {
                        state.cursors().put(key + ":next", continuation);
                        state.cursors().put(key + ":overlap", replay);
                        state.cursors().put(key + ":scanStart", scanStart);
                        return null;
                    });
                }
            }
        }
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var reviewScan = executor.submit(() -> options.reviews().enabled() ? reviews.poll(selected) : 0);
            final var discussionScan =
                    executor.submit(() -> options.discussions().enabled() ? discussions.poll(selected) : 0);
            try {
                return created + reviewScan.get() + discussionScan.get();
            } catch (final InterruptedException error) {
                reviewScan.cancel(true);
                discussionScan.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Polling interrupted", error);
            } catch (final ExecutionException error) {
                if (error.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw new IllegalStateException("Polling failed", error.getCause());
            }
        }
    }
}
