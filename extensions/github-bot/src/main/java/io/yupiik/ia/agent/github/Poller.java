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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Polling never calls a model. Identity checks precede durable enqueue. */
public final class Poller {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final GithubBotConfiguration conf;
    private final PollOptions options;
    private final TaskStore store;
    private final GitHubClient github;
    private final Clock clock;
    private final BotAudit audit;

    /** The optional audit sink is shared with the review-summary poller. */
    public Poller(
            final GithubBotConfiguration conf,
            final PollOptions options,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        this.conf = conf;
        this.options = options;
        this.store = store;
        this.github = github;
        this.clock = clock;
        this.audit = audit;
    }

    public int poll() {
        if (options.pageBudget() <= 0) {
            throw new IllegalArgumentException("Positive page budget required");
        }
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
        final var sources = csv(conf.repositories().discovery()).stream()
                .map(value -> {
                    final var parts = value.split(":", -1);
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("Expected org:login or user:login discovery source");
                    }
                    final var kind =
                            switch (parts[0]) {
                                case "org" -> RepositorySelector.SourceKind.ORGANIZATION;
                                case "user" -> RepositorySelector.SourceKind.USER;
                                default -> throw new IllegalArgumentException("Expected org or user discovery source");
                            };
                    return new RepositorySelector.Source(kind, parts[1]);
                })
                .toList();
        final var visibility = csv(conf.repositories().visibility()).stream()
                .map(value -> RepositorySelector.Visibility.valueOf(value.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toSet());
        if (sources.isEmpty()
                && !conf.repositories().excludeArchived()
                && !conf.repositories().excludeForks()
                && conf.repositories().includes().isBlank()
                && conf.repositories().excludes().isBlank()
                && visibility.size() == RepositorySelector.Visibility.values().length) {
            final var explicit = csv(conf.repositories().explicit());
            for (final var repo : explicit) {
                if (!REPOSITORY_NAME.matcher(repo).matches()) {
                    throw new IllegalArgumentException("Invalid explicit repository");
                }
            }
            return explicit.stream().distinct().toList();
        }
        final var includes = new ArrayList<>(csv(conf.repositories().explicit()));
        includes.addAll(csv(conf.repositories().includes()));
        return new RepositorySelector(
                        github,
                        sources,
                        includes,
                        csv(conf.repositories().excludes()),
                        visibility,
                        Duration.ZERO,
                        clock,
                        conf.repositories().excludeArchived(),
                        conf.repositories().excludeForks())
                .select().stream().map(RepositorySelector.Repository::fullName).toList();
    }

    private static List<String> csv(final String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .toList();
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
                                || !new TeamAuthorization(
                                                github, conf.authorization().teams())
                                        .allows(user.get("id"), String.valueOf(user.get("login")))) {
                            if (audit != null) {
                                final var identifiers = new LinkedHashMap<String, Object>();
                                identifiers.put("repository", repo);
                                if (user.get("id") != null) {
                                    identifiers.put("actorId", user.get("id"));
                                }
                                audit.record("auth-denied", identifiers);
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
                        final var task = new LinkedHashMap<String, Object>();
                        task.put("repository", repo);
                        task.put("number", number);
                        task.put("reference", ref);
                        task.put("kind", invocation.kind());
                        task.put("request", invocation.request());
                        task.put("actorId", user.get("id"));
                        task.put("batchId", batch);
                        task.put("createdAt", clock.instant().toString());
                        final var receipt = key + ":" + item.get("id");
                        final var id = store.transaction(state -> {
                            if (state.receipts().containsKey(receipt)) {
                                return null;
                            }
                            return state.enqueue(receipt, task);
                        });
                        if (id != null) {
                            created++;
                            if (audit != null) {
                                audit.record(
                                        "enqueued",
                                        Map.of(
                                                "id",
                                                id,
                                                "repository",
                                                repo,
                                                "number",
                                                number,
                                                "actorId",
                                                user.get("id")));
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
        return created
                + (options.pollReviews()
                        ? new ReviewSummaryPoller(conf, options, store, github, clock, audit).poll(selected)
                        : 0)
                + (options.pollDiscussions()
                        ? new DiscussionPoller(conf, options, store, github, clock, audit).poll(selected)
                        : 0);
    }
}
