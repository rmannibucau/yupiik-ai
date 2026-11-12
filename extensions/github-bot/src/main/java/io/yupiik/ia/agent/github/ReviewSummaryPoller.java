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
import java.nio.channels.FileChannel;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Opt-in native review-summary mentions; polling never calls a model.
 *
 * <p>Pull discovery and each discovered pull's reviews have independent durable continuations and
 * at most pageBudget requests per poll. Discovered pulls are retained before discovery advances, so
 * a large review backlog cannot be abandoned when discovery moves on. Network requests never hold
 * a task-store transaction. Failed pages are replayed; receipts outlive tasks.
 *
 * <p>GitHub uses mutable offset pagination, not snapshots. Every completed traversal restarts from
 * page one, retaining initialSince minus five minutes forever rather than advancing a watermark.
 * This deliberately replays old pulls/reviews (including closed pulls), catching page shifts and
 * reviews submitted after a pending review was seen. It costs increasing scan work and retained
 * endpoint cursors, but does not truncate large backlogs at a time watermark or page budget. As with
 * any offset traversal, discovery under perpetual adversarial page movement cannot be guaranteed;
 * repeated traversals recover shifted entries once the listing stabilizes.
 */
public final class ReviewSummaryPoller {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]*");

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final GithubBotConfiguration conf;
    private final PollOptions options;
    private final TaskStore store;
    private final GitHubClient github;
    private final Clock clock;
    private final BotAudit audit;

    /** A null audit sink preserves polling without audit output. */
    public ReviewSummaryPoller(
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

    public int poll(final List<String> repositories) {
        if (!options.pollReviews()) {
            return 0;
        }
        if (options.pageBudget() <= 0) {
            throw new IllegalArgumentException("Positive page budget required");
        }
        final Set<String> allowed = Arrays.stream(conf.authorization().userIds().split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            throw new IllegalStateException("No authorized immutable user IDs configured; polling denied");
        }
        final var repos = List.copyOf(repositories).stream().distinct().toList();
        for (final var repo : repos) {
            if (!REPOSITORY_NAME.matcher(repo).matches()) {
                throw new IllegalArgumentException("Invalid explicit repository");
            }
        }
        final var authorization =
                new TeamAuthorization(github, conf.authorization().teams());
        final var path = Path.of(conf.home()).toAbsolutePath().normalize().resolve("review-summary-poll.lock");
        final var local = LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        local.lock();
        try (final var channel = FileChannel.open(
                        path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final var lock = channel.lock()) {
            final var since = Instant.parse(store.transaction(state -> String.valueOf(state.cursors()
                            .computeIfAbsent(
                                    "initialSince",
                                    ignored -> "now".equals(options.since())
                                            ? clock.instant().toString()
                                            : Instant.parse(options.since()).toString()))))
                    .minusSeconds(300);
            final var batch = UUID.randomUUID().toString();
            int created = 0;
            for (final var repo : repos) {
                discover(repo);
                final var prefix = repo + "/pulls/";
                final var endpoints = store.transaction(state -> state.cursors().keySet().stream()
                        .filter(key -> key.startsWith(prefix) && key.endsWith("/reviews:known"))
                        .map(key -> key.substring(0, key.length() - ":known".length()))
                        .toList());
                for (final var endpoint : endpoints) {
                    final var number = Integer.parseInt(endpoint.substring(prefix.length(), endpoint.lastIndexOf('/')));
                    created += reviews(repo, number, endpoint, since, allowed, authorization, batch);
                }
            }
            return created;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            local.unlock();
        }
    }

    private void discover(final String repo) {
        final var key = repo + "/review-summary-pulls";
        String next = continuation(key, "/repos/" + repo + "/pulls?state=all&sort=updated&direction=desc&per_page=100");
        for (int pages = 0; next != null && pages < options.pageBudget(); pages++) {
            final var page = github.page(next);
            final var pulls = items(page);
            // Register every pull before committing the next discovery page, even if a later review GET fails.
            store.transaction(state -> {
                for (final var raw : pulls) {
                    final var number = positiveId(TaskStore.map(raw).get("number"));
                    state.cursors().put(repo + "/pulls/" + number + "/reviews:known", "true");
                }
                saveNext(state, key, page.next());
                return null;
            });
            next = page.next();
        }
    }

    private int reviews(
            final String repo,
            final int number,
            final String endpoint,
            final Instant since,
            final Set<String> allowed,
            final TeamAuthorization authorization,
            final String batch) {
        String next = continuation(endpoint, "/repos/" + endpoint + "?per_page=100");
        int created = 0;
        for (int pages = 0; next != null && pages < options.pageBudget(); pages++) {
            final var page = github.page(next);
            for (final var raw : items(page)) {
                final var item = TaskStore.map(raw);
                if ("PENDING".equalsIgnoreCase(String.valueOf(item.get("state")))
                        || "DRAFT".equalsIgnoreCase(String.valueOf(item.get("state")))
                        || !(item.get("submitted_at") instanceof String submitted)
                        || Instant.parse(submitted).isBefore(since)) {
                    continue;
                }
                final var user = item.get("user") instanceof Map<?, ?>
                        ? TaskStore.map(item.get("user"))
                        : Map.<String, Object>of();
                final var parsed = MentionParser.parse(String.valueOf(item.getOrDefault("body", "")), conf.login());
                if (parsed.isEmpty()) {
                    continue;
                }
                if (!allowed.contains(String.valueOf(user.get("id")))
                        || conf.login().equalsIgnoreCase(String.valueOf(user.get("login")))
                        || "Bot".equals(user.get("type"))) {
                    auditDenied(repo, number, user);
                    continue;
                }
                final var id = positiveId(item.get("id"));
                final var receipt = repo + "/reviews/" + id;
                if (store.transaction(state -> state.receipts().containsKey(receipt))) {
                    continue;
                }
                if (!authorization.allows(user.get("id"), String.valueOf(user.get("login")))) {
                    auditDenied(repo, number, user);
                    continue;
                }
                final var ref = String.valueOf(item.get("html_url"));
                validateReference(ref, repo, number, id);
                final var invocation = parsed.orElseThrow();
                final var task = new LinkedHashMap<String, Object>();
                task.put("repository", repo);
                task.put("number", number);
                task.put("reference", ref);
                task.put("kind", invocation.kind());
                task.put("request", invocation.request());
                task.put("actorId", user.get("id"));
                task.put("batchId", batch);
                task.put("createdAt", clock.instant().toString());
                final var taskId = store.transaction(state -> {
                    if (state.receipts().containsKey(receipt)) {
                        return null;
                    }
                    return state.enqueue(receipt, task);
                });
                if (taskId != null) {
                    created++;
                    if (audit != null) {
                        audit.record(
                                "enqueued",
                                Map.of("id", taskId, "repository", repo, "number", number, "actorId", user.get("id")));
                    }
                }
            }
            store.transaction(state -> {
                saveNext(state, endpoint, page.next());
                return null;
            });
            next = page.next();
        }
        return created;
    }

    private void auditDenied(final String repo, final int number, final Map<String, Object> user) {
        if (audit != null) {
            final var identifiers = new LinkedHashMap<String, Object>();
            identifiers.put("repository", repo);
            identifiers.put("number", number);
            if (user.get("id") != null) {
                identifiers.put("actorId", user.get("id"));
            }
            audit.record("auth-denied", identifiers);
        }
    }

    private String continuation(final String key, final String first) {
        return store.transaction(state -> String.valueOf(state.cursors().getOrDefault(key + ":next", first)));
    }

    private void saveNext(final TaskStore.State state, final String key, final String next) {
        if (next == null) {
            state.cursors().remove(key + ":next");
        } else {
            state.cursors().put(key + ":next", next);
        }
    }

    private List<?> items(final GitHubClient.Page page) {
        if (!(page.body() instanceof List<?> list)) {
            throw new IllegalStateException("Expected GitHub list");
        }
        return list;
    }

    private String positiveId(final Object value) {
        final var id = String.valueOf(value);
        if (!POSITIVE_ID.matcher(id).matches()) {
            throw new IllegalStateException("Expected positive GitHub identifier");
        }
        return id;
    }

    private void validateReference(final String ref, final String repo, final int number, final String id) {
        final var uri = URI.create(ref);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !("github.com".equalsIgnoreCase(uri.getHost())
                        || URI.create(conf.api()).getHost().equalsIgnoreCase(uri.getHost()))
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getQuery() != null
                || !uri.getRawPath().equalsIgnoreCase("/" + repo + "/pull/" + number)
                || !("pullrequestreview-" + id).equals(uri.getRawFragment())) {
            throw new IllegalStateException("Unexpected review reference");
        }
    }
}
