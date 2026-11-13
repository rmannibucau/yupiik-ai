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
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamAuthorizationTest {
    @Test
    void bindsMembershipToImmutableIdentityAndRequiresActiveState() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var path = exchange.getRequestURI().getPath();
            final var body = path.equals("/user/42")
                    ? "{\"id\":42,\"login\":\"alice\"}"
                    : path.equals("/orgs/acme/teams/dev/memberships/alice")
                            ? "{\"state\":\"active\"}"
                            : "{\"state\":\"pending\"}";
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(
                    path.contains("/missing/") ? 404 : path.contains("/forbidden/") ? 403 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = new JsonMapperImpl(List.of(), key -> Optional.empty());
                final var github = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "test",
                        BotTestConfiguration.CLOCK)) {
            assertTrue(BotTestConfiguration.authorization(github, "").allows(null, null));
            assertTrue(BotTestConfiguration.authorization(github, "acme/dev").allows(42, "alice"));
            assertTrue(BotTestConfiguration.authorization(github, "acme/dev").allows(42, null));
            assertFalse(BotTestConfiguration.authorization(github, "acme/dev").allows(42, "renamed"));
            assertFalse(
                    BotTestConfiguration.authorization(github, "acme/pending").allows(42, "alice"));
            assertFalse(BotTestConfiguration.authorization(github, "acme/dev").allows(43, "alice"));
            assertTrue(BotTestConfiguration.authorization(github, "acme/missing,acme/dev")
                    .allows(42, "alice"));
            assertFalse(
                    BotTestConfiguration.authorization(github, "acme/missing").allows(42, "alice"));
            assertThrows(
                    GitHubClient.HttpException.class,
                    () -> BotTestConfiguration.authorization(github, "acme/forbidden,acme/dev")
                            .allows(42, "alice"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BotTestConfiguration.authorization(github, "../../bad")
                            .allows(42, "alice"));
        } finally {
            server.stop(0);
        }
    }
}
