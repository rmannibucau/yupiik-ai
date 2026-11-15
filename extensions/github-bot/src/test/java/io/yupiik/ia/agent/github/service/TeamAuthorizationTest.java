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
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TeamAuthorizationTest {
    private static final String PERMISSION = "/repos/owner/repo/collaborators/alice/permission";
    private static final String MEMBERSHIP = "/orgs/acme/teams/dev/memberships/alice";

    @Test
    void explicitIdAlwaysWinsWithoutAnyLookupsOrRestrictions() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy(" 42 , 7 ", "invalid/team/slug", false, 7200);
            assertTrue(policy.allows(null, 42, "untrusted-login"));
            assertTrue(policy.allows("other/repository", "7", null));
            assertFalse(policy.allows("owner/repo", 43, "alice"));
            assertFalse(policy.allows("owner/repo", null, null));
            assertTrue(fixture.requests.isEmpty());
            assertTrue(fixture.policy("42", "acme/dev", true, 7200).allows("owner/repo", 42, "alice"));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void effectiveBasePermissionNotCustomRoleNameDeterminesWriterAccess() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "", true, 0);
            for (final var permission : List.of("write", "admin")) {
                for (final var role : List.of("write", "maintain", "custom-team-writer")) {
                    fixture.responses.put(PERMISSION, permission(permission, role, 42, "alice"));
                    assertTrue(policy.allows("owner/repo", 42, "ALICE"), permission + "/" + role);
                }
            }
            for (final var permission : List.of("read", "none", "triage", "maintain", "unknown", "")) {
                fixture.responses.put(PERMISSION, permission(permission, "admin", 42, "alice"));
                assertFalse(policy.allows("owner/repo", 42, "alice"), permission);
            }
            fixture.responses.put(PERMISSION, Map.of("role_name", "write", "user", user(42, "alice")));
            assertFalse(policy.allows("owner/repo", 42, "alice"));
        }
    }

    @Test
    void bindsBothLookupResponsesToImmutableIdAndCurrentLogin() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "", true, 0);
            assertTrue(policy.allows("owner/repo", 42, null));
            assertFalse(policy.allows("owner/repo", 42, "old-or-reused-login"));
            fixture.responses.put("/user/42", user(99, "alice"));
            assertFalse(policy.allows("owner/repo", 42, "alice"));
            fixture.responses.put("/user/42", user(42, "../../bad"));
            assertFalse(policy.allows("owner/repo", 42, null));
            fixture.responses.put("/user/42", user(42, "alice"));
            for (final var invalid : List.of(
                    permission("write", "write", 99, "alice"),
                    permission("write", "write", 42, "someone-else"),
                    Map.of("permission", "write"))) {
                fixture.responses.put(PERMISSION, invalid);
                assertFalse(policy.allows("owner/repo", 42, null));
            }
            fixture.requests.clear();
            assertFalse(policy.allows("owner/repo/../other", 42, "alice"));
            assertFalse(policy.allows("owner/repo", "../../bad", "alice"));
            assertFalse(policy.allows("owner/repo", 42, "alice/permission"));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void optionalTeamsRestrictOnlyWriterPathAndRequireAnActiveMembership() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "acme/missing,acme/dev", true, 0);
            assertTrue(policy.allows("owner/repo", 42, "alice"));
            fixture.responses.put(MEMBERSHIP, Map.of("state", "pending"));
            assertFalse(policy.allows("owner/repo", 42, "alice"));
            fixture.responses.put(MEMBERSHIP, Map.of("state", "active"));
            fixture.responses.put(PERMISSION, permission("read", "read", 42, "alice"));
            fixture.requests.clear();
            assertFalse(policy.allows("owner/repo", 42, "alice"));
            assertFalse(fixture.requests.contains(MEMBERSHIP), "Team membership alone cannot grant access");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.policy("", "../../bad", true, 0).allows("owner/repo", 42, "alice"));
        }
    }

    @Test
    void cacheIncludesRepositoryActorIdentityAndTeamsWithFixedExpiration() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "acme/dev", true, 7200);
            assertTrue(policy.allows("Owner/Repo", 42, "alice"));
            assertEquals(List.of("/user/42", PERMISSION, MEMBERSHIP), fixture.requests);
            assertTrue(policy.allows("owner/repo", 42, null));
            assertFalse(policy.allows("owner/repo", 42, "reused"));
            assertEquals(3, fixture.requests.size());
            fixture.responses.put(PERMISSION, permission("read", "read", 42, "alice"));
            fixture.clock.advanceSeconds(7199);
            assertTrue(policy.allows("OWNER/REPO", 42, "alice"));
            assertEquals(3, fixture.requests.size());
            fixture.clock.advanceSeconds(1);
            assertFalse(policy.allows("owner/repo", 42, "alice"));
            assertEquals(5, fixture.requests.size(), "Cache hits must not extend expiration");
            fixture.responses.put(PERMISSION, permission("write", "write", 42, "alice"));
            assertFalse(policy.allows("owner/repo", 42, null), "Completed denials are cached too");
            assertFalse(policy.allows("owner/other", 42, null), "Another repository cannot reuse the allow");
            assertFalse(policy.allows("owner/repo", 43, null), "Another actor cannot reuse the allow");
            assertTrue(fixture.requests.contains("/repos/owner/other/collaborators/alice/permission"));
            assertTrue(fixture.requests.contains("/user/43"));
            fixture.clock.advanceSeconds(7200);
            assertTrue(policy.allows("owner/repo", 42, "alice"), "Grant takes effect after cached denial expires");
        }
    }

    @Test
    void suppliedLoginMismatchDoesNotPoisonCachedPermission() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "", true, 7200);
            assertFalse(policy.allows("owner/repo", 42, "old-login"));
            assertTrue(policy.allows("owner/repo", 42, "alice"));
            assertEquals(2, fixture.requests.size());
        }
    }

    @Test
    void zeroTtlDisablesCacheAndMembershipRevocationTakesEffectAfterExpiry() throws Exception {
        try (final var fixture = new Fixture()) {
            final var uncached = fixture.policy("", "", true, 0);
            assertTrue(uncached.allows("owner/repo", 42, null));
            assertTrue(uncached.allows("owner/repo", 42, null));
            assertEquals(4, fixture.requests.size());
            final var cached = fixture.policy("", "acme/dev", true, 7200);
            assertTrue(cached.allows("owner/repo", 42, null));
            fixture.responses.put(MEMBERSHIP, Map.of("state", "pending"));
            assertTrue(cached.allows("owner/repo", 42, null));
            fixture.clock.advanceSeconds(7200);
            assertFalse(cached.allows("owner/repo", 42, null));
        }
    }

    @Test
    void tokenCapabilityErrorsLogAtInfoDenyWithoutCachingAndNeverAffectExplicitIds() throws Exception {
        final var records = new CopyOnWriteArrayList<LogRecord>();
        final var logger = Logger.getLogger(TeamAuthorization.class.getName());
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try (final var fixture = new Fixture()) {
            for (final var path : List.of("/user/42", PERMISSION, MEMBERSHIP)) {
                for (final var status : List.of(401, 403)) {
                    final var policy = fixture.policy("", "acme/dev", true, 7200);
                    fixture.statuses.put(path, status);
                    assertFalse(policy.allows("owner/repo", 42, "alice"));
                    assertFalse(policy.allows("owner/repo", 42, "alice"));
                    fixture.statuses.remove(path);
                    assertTrue(policy.allows("owner/repo", 42, "alice"), "Capability failure must not be cached");
                    fixture.requests.clear();
                    fixture.statuses.put(path, status);
                    assertTrue(fixture.policy("42", "acme/dev", true, 7200).allows("owner/repo", 42, null));
                    assertTrue(fixture.requests.isEmpty());
                    fixture.statuses.remove(path);
                }
            }
            assertEquals(12, records.size());
            assertTrue(records.stream()
                    .allMatch(record -> record.getLevel() == Level.INFO
                            && record.getMessage().contains("token lacks capability")
                            && record.getMessage().contains("owner/repo")));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void missingResourcesDenyOtherFailuresPropagateAndExpiredAllowIsNeverFallback() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "", true, 7200);
            assertTrue(policy.allows("owner/repo", 42, "alice"));
            fixture.clock.advanceSeconds(7200);
            fixture.statuses.put(PERMISSION, 403);
            assertFalse(policy.allows("owner/repo", 42, "alice"));
            fixture.statuses.put(PERMISSION, 404);
            assertFalse(policy.allows("owner/repo", 42, "alice"));
            fixture.statuses.put(PERMISSION, 422);
            assertEquals(
                    422,
                    assertThrows(GitHubClient.HttpException.class, () -> policy.allows("owner/repo", 42, "alice"))
                            .status());
            fixture.statuses.remove(PERMISSION);
            assertTrue(policy.allows("owner/repo", 42, "alice"));
        }
    }

    @Test
    void rateLimit403PropagatesAsDeferredRatherThanTokenCapabilityDenial() throws Exception {
        try (final var fixture = new Fixture()) {
            fixture.statuses.put(PERMISSION, 403);
            fixture.retryAfter.set("3600");
            final var policy = fixture.policy("", "", true, 7200);
            final var error = assertThrows(
                    GitHubClient.DeferredRequestException.class, () -> policy.allows("owner/repo", 42, "alice"));
            assertEquals(403, error.status());
            fixture.statuses.remove(PERMISSION);
            fixture.retryAfter.set(null);
            fixture.clock.advanceSeconds(3601);
            assertTrue(policy.allows("owner/repo", 42, "alice"));
        }
    }

    @Test
    void concurrentCallersShareCompletedDecisionsSafely() throws Exception {
        try (final var fixture = new Fixture();
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var policy = fixture.policy("", "", true, 7200);
            final var results = executor.invokeAll(IntStream.range(0, 20)
                    .<Callable<Boolean>>mapToObj(ignored -> () -> policy.allows("owner/repo", 42, "alice"))
                    .toList());
            for (final var result : results) {
                assertTrue(result.get());
            }
            final var count = fixture.requests.size();
            assertTrue(policy.allows("owner/repo", 42, "alice"));
            assertEquals(count, fixture.requests.size());
        }
    }

    @Test
    void cacheEvictsOldDecisionsInsteadOfGrowingWithoutBound() throws Exception {
        try (final var fixture = new Fixture()) {
            final var policy = fixture.policy("", "", true, 7200);
            assertTrue(policy.allows("owner/repo", 42, "alice"));
            // A single mismatched immutable identity response per entry keeps this bounded-cache test small.
            for (int actor = 10000; actor < 14096; actor++) {
                fixture.responses.put("/user/" + actor, user(42, "alice"));
                assertFalse(policy.allows("owner/repo", actor, "alice"));
            }
            fixture.responses.put(PERMISSION, permission("read", "read", 42, "alice"));
            assertFalse(policy.allows("owner/repo", 42, "alice"), "Old allow was evicted before its TTL");
        }
    }

    private static Map<String, Object> user(final int id, final String login) {
        return Map.of("id", id, "login", login);
    }

    private static Map<String, Object> permission(
            final String permission, final String role, final int id, final String login) {
        return Map.of("permission", permission, "role_name", role, "user", user(id, login));
    }

    private static final class Fixture implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final JsonMapperImpl json = new JsonMapperImpl(List.of(), key -> Optional.empty());
        private final Map<String, Object> responses = new ConcurrentHashMap<>();
        private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> retryAfter = new AtomicReference<>();
        private final HttpServer server;
        private final GitHubClient github;

        private Fixture() throws Exception {
            responses.put("/user/42", user(42, "alice"));
            responses.put(PERMISSION, permission("write", "maintain", 42, "alice"));
            responses.put(MEMBERSHIP, Map.of("state", "active"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                final var path = exchange.getRequestURI().getPath();
                requests.add(path);
                final var response = responses.get(path);
                final var bytes =
                        json.toString(response == null ? Map.of() : response).getBytes(StandardCharsets.UTF_8);
                // Avoid keep-alive delayed ACK overhead in the high-volume eviction test.
                exchange.getResponseHeaders().set("Connection", "close");
                final var retry = retryAfter.get();
                if (retry != null && statuses.containsKey(path)) {
                    exchange.getResponseHeaders().set("Retry-After", retry);
                }
                exchange.sendResponseHeaders(statuses.getOrDefault(path, response == null ? 404 : 200), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), json, () -> "test", clock);
        }

        private TeamAuthorization policy(final String ids, final String teams, final boolean writers, final long ttl) {
            final var configuration = new GithubBotConfiguration(
                    "",
                    "https://api.github.com",
                    "bot",
                    0,
                    new GithubBotConfiguration.Auth(
                            GithubBotConfiguration.AuthType.pat, "GH_TOKEN", "", 0, "", "GH_AGENT_TOKEN"),
                    new GithubBotConfiguration.Authorization(ids, teams, writers, ttl),
                    new GithubBotConfiguration.Repositories(
                            "owner/repo", "", "", "", List.of(RepositorySelector.Visibility.values()), false, false));
            return new TeamAuthorization(github, configuration, clock);
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T12:00:00Z"));

        private void advanceSeconds(final long seconds) {
            now.updateAndGet(value -> value.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
