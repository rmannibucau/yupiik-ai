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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.security.SecretRegistry;
import io.yupiik.ia.agent.core.security.SecretSessionModes;
import io.yupiik.ia.agent.core.security.SecretVault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubSecretValuesContributorTest {
    @Test
    void contributesCredentialsAndUsesSharedRedactor(@TempDir final Path directory) throws Exception {
        final var builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Probe.class.getName(),
                        directory.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // Deliberately use names which do not look like secrets to generic environment harvesting.
        final var env = builder.environment();
        env.put("YCA_TEST_HOST", "host-credential-123456");
        env.put("YCA_TEST_AGENT", "agent-credential-654321");
        env.put("GITHUB_BOT_FORK_TOKEN", "fork-credential-987654");
        env.put("YCA_TEST_BLANK", "   ");
        env.remove("YCA_TEST_ABSENT");
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Credential probe timed out");
            assertEquals(0, process.exitValue(), "Credential harvesting/redaction probe failed");
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    /** Separate JVM gives the contributor a controlled real environment without reflective mutation. */
    public static class Probe {
        public static void main(final String[] args) throws Exception {
            final var directory = Path.of(args[0]);
            final var key = directory.resolve("private.pem");
            final var pem = "-----BEGIN " + "PRIVATE KEY-----\n"
                    + "synthetic-key-material-for-masking\n"
                    + "-----END " + "PRIVATE KEY-----";
            Files.writeString(key, pem + "\n");
            final var contributor = contributor("YCA_TEST_HOST", "YCA_TEST_AGENT", key.toString());
            final var expected = Set.of(
                    "host-credential-123456", "agent-credential-654321", "fork-credential-987654", pem, pem + "\n");
            check(Set.copyOf(contributor.values()).equals(expected));

            final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of(contributor));
            final var modes = new SecretSessionModes();
            final var redactor = new SecretRedactor(registry, modes);
            // Exercise the existing masker with exact harvested values, not prefix/shape matchers.
            modes.set("github-test", SecretRedactionMode.REDACT);
            final var text = "host=host-credential-123456 agent=agent-credential-654321 fork=fork-credential-987654\n"
                    + pem + "\n";
            final var masked = redactor.redact(text, "github-test");
            check(!masked.equals(text));
            for (final var secret : expected) {
                check(!masked.contains(secret));
            }
            check(redactor.restore(masked, "github-test").equals(text));

            // Invalid, absent, empty and non-file key locations must preserve other harvested values.
            final var forkOnly = Set.of("fork-credential-987654");
            for (final var location :
                    List.of("", "\0", directory.resolve("missing").toString(), directory.toString())) {
                check(Set.copyOf(contributor("YCA_TEST_BLANK", "YCA_TEST_ABSENT", location)
                                .values())
                        .equals(forkOnly));
            }
            Files.writeString(key, "   \n");
            check(Set.copyOf(contributor("", "", key.toString()).values()).equals(forkOnly));
            check(Set.copyOf(contributor("", "", "").values()).equals(forkOnly));
        }

        private static GithubSecretValuesContributor contributor(
                final String host, final String agent, final String key) {
            final var properties = Map.of(
                    "github.auth.tokenEnv",
                    host,
                    "github.auth.agentTokenEnv",
                    agent,
                    "github.auth.privateKeyFile",
                    key);
            final var configuration = new GithubBotConfiguration$FusionConfigurationFactory(
                            name -> Optional.ofNullable(properties.get(name)))
                    .get();
            return new GithubSecretValuesContributor(configuration);
        }

        private static void check(final boolean condition) {
            if (!condition) {
                throw new AssertionError("Credential probe failed");
            }
        }
    }
}
