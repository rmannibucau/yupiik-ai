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

import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/** Owns host authentication and its HTTP clients; never passed into an executor. */
public final class GithubRuntime implements AutoCloseable {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_-][A-Za-z0-9_.-]*");
    private static final Pattern ORGANIZATION_SOURCE = Pattern.compile("org:[A-Za-z0-9_-]+");

    private final AuthTokenProvider authentication;
    private final GitHubClient client;

    public GithubRuntime(final GithubBotConfiguration conf, final JsonMapper json, final Clock clock) {
        authentication = switch (conf.auth().type()) {
            case "pat" -> {
                if (conf.auth().tokenEnv() == null || conf.auth().tokenEnv().isBlank()) {
                    throw new IllegalArgumentException("github.auth.tokenEnv is required for PAT authentication");
                }
                yield AuthTokenProvider.pat(() -> System.getenv(conf.auth().tokenEnv()));
            }
            case "app" -> {
                if (conf.auth().appId().isBlank()
                        || conf.auth().installationId() < 0
                        || conf.auth().privateKeyFile().isBlank()) {
                    throw new IllegalArgumentException(
                            "App authentication requires appId, non-negative installationId and privateKeyFile");
                }
                final var repository = conf.repositories().explicit() == null
                        ? ""
                        : conf.repositories().explicit().trim();
                final var discovery = conf.repositories().discovery() == null
                        ? ""
                        : conf.repositories().discovery().trim();
                final boolean fromRepository = discovery.isEmpty()
                        && REPOSITORY_NAME.matcher(repository).matches();
                final boolean fromOrganization = repository.isEmpty()
                        && ORGANIZATION_SOURCE.matcher(discovery).matches();
                if (conf.auth().installationId() == 0 && !fromRepository && !fromOrganization) {
                    throw new IllegalArgumentException(
                            "github.auth.installationId=0 requires exactly one explicit github.repositories.explicit"
                                    + " owner/repository without globs or discovery, or one github.repositories.discovery"
                                    + " org source with no explicit repositories; set a positive installationId for"
                                    + " ambiguous selections");
                }
                try {
                    final var pem = Files.readString(Path.of(conf.auth().privateKeyFile()))
                            .trim();
                    final var begin = "-----BEGIN " + "PRIVATE KEY-----";
                    final var end = "-----END " + "PRIVATE KEY-----";
                    if (!pem.startsWith(begin) || !pem.endsWith(end)) {
                        throw new IllegalArgumentException("privateKeyFile must contain a PEM PKCS#8 RSA private key");
                    }
                    final var encoded = pem.substring(begin.length(), pem.length() - end.length())
                            .replaceAll("\\s", "");
                    final var key = KeyFactory.getInstance("RSA")
                            .generatePrivate(
                                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
                    if (conf.auth().installationId() == 0) {
                        yield fromRepository
                                ? AuthTokenProvider.appForRepository(
                                        URI.create(conf.api()),
                                        json,
                                        conf.auth().appId(),
                                        repository,
                                        key,
                                        clock)
                                : AuthTokenProvider.appForOrganization(
                                        URI.create(conf.api()),
                                        json,
                                        conf.auth().appId(),
                                        discovery.substring(4),
                                        key,
                                        clock);
                    }
                    yield AuthTokenProvider.app(
                            URI.create(conf.api()),
                            json,
                            conf.auth().appId(),
                            conf.auth().installationId(),
                            key,
                            clock);
                } catch (final IOException e) {
                    throw new UncheckedIOException("Unable to read GitHub App private key", e);
                } catch (final GeneralSecurityException e) {
                    throw new IllegalArgumentException("Invalid PKCS#8 RSA private key", e);
                }
            }
            default -> throw new IllegalArgumentException("github.auth.type must be pat or app");
        };
        try {
            client = new GitHubClient(
                    URI.create(conf.api()), json, authentication, Duration.ofMillis(conf.requestIntervalMillis()));
        } catch (final RuntimeException | Error e) {
            authentication.close();
            throw e;
        }
    }

    public GitHubClient client() {
        return client;
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            authentication.close();
        }
    }
}
