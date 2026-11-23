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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationLoader;
import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import io.yupiik.ia.agent.core.security.EnvironmentSecretValuesContributor;
import io.yupiik.ia.agent.core.security.SecretNames;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.security.SecretRegistry;
import io.yupiik.ia.agent.core.security.SecretSessionModes;
import io.yupiik.ia.agent.core.security.SecretVault;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.support.TestJava;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubSecretValuesContributorTest {
    @Test
    void programmaticCredentialsReachSharedRegistryAndRedactor() {
        final var config = Configuration.of(Map.of(
                "github.auth.tokenEnv", "YCA_CONFIG_HOST",
                "github.auth.agentTokenEnv", "YCA_CONFIG_AGENT",
                "YCA_CONFIG_HOST", "configured-host-credential-123456",
                "YCA_CONFIG_AGENT", "configured-agent-credential-654321"));
        final var conf = ConfigurationLoader.load(GithubBotConfiguration.class, config);
        final var contributor = new GithubSecretValuesContributor(conf, config);
        final var expected = Set.of("configured-host-credential-123456", "configured-agent-credential-654321");
        assertEquals(expected, Set.copyOf(contributor.values()));
        final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of(contributor));
        final var modes = new SecretSessionModes();
        modes.set("configured", SecretRedactionMode.REDACT);
        final var redactor = new SecretRedactor(registry, modes);
        final var text = String.join(" ", expected);
        final var masked = redactor.redact(text, "configured");
        for (final var secret : expected) {
            assertTrue(!masked.contains(secret));
        }
        assertEquals(text, redactor.restore(masked, "configured"));
    }

    @Test
    void rereadsConfiguredCredentialsOnEachHarvest() {
        final var values = new HashMap<>(Map.of(
                "github.auth.tokenEnv", "YCA_CONFIG_HOST",
                "github.auth.agentTokenEnv", "YCA_CONFIG_AGENT",
                "YCA_CONFIG_HOST", "configured-host-first",
                "YCA_CONFIG_AGENT", "configured-agent-first"));
        final Configuration config = name -> Configuration.of(values).get(name);
        final var conf = ConfigurationLoader.load(GithubBotConfiguration.class, config);
        final var contributor = new GithubSecretValuesContributor(conf, config);
        assertEquals(Set.of("configured-host-first", "configured-agent-first"), Set.copyOf(contributor.values()));
        values.put("YCA_CONFIG_HOST", "configured-host-rotated");
        values.put("YCA_CONFIG_AGENT", "   ");
        assertEquals(List.of("configured-host-rotated"), List.copyOf(contributor.values()));
    }

    @Test
    void failedConfigurationLookupDoesNotPreventRemainingHarvesting() {
        final var conf = ConfigurationLoader.load(
                GithubBotConfiguration.class,
                Configuration.of(Map.of(
                        "github.auth.tokenEnv", "unavailable",
                        "github.auth.agentTokenEnv", "available")));
        final Configuration config = name -> {
            if ("unavailable".equals(name)) {
                throw new IllegalStateException("sensitive-source-diagnostic");
            }
            return Optional.of("configured-agent-credential-654321");
        };
        assertEquals(
                List.of("configured-agent-credential-654321"),
                List.copyOf(new GithubSecretValuesContributor(conf, config).values()));
    }

    @Test
    void contributesCredentialsAndUsesSharedRedactor(@TempDir final Path directory) throws Exception {
        final var builder = new ProcessBuilder(TestJava.javaCommand(
                        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), directory.toString()))
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
            final var expected = Set.of("host-credential-123456", "agent-credential-654321", pem, pem + "\n");
            check(Set.copyOf(contributor.values()).equals(expected));

            final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of(contributor));
            final var modes = new SecretSessionModes();
            final var redactor = new SecretRedactor(registry, modes);
            // Exercise the existing masker with exact harvested values, not prefix/shape matchers.
            modes.set("github-test", SecretRedactionMode.REDACT);
            final var text = "host=host-credential-123456 agent=agent-credential-654321\n" + pem + "\n";
            final var masked = redactor.redact(text, "github-test");
            check(!masked.equals(text));
            for (final var secret : expected) {
                check(!masked.contains(secret));
            }
            check(redactor.restore(masked, "github-test").equals(text));

            // Invalid, absent, empty and non-file key locations must preserve other harvested values.
            final var defaults = ConfigurationLoader.load(CodingAgentConfiguration.class, ignored -> Optional.empty());
            final var names = new SecretNames(defaults);
            check(names.isSecretName("GITHUB_BOT_FORK_TOKEN"));
            check(new EnvironmentSecretValuesContributor(defaults, names)
                    .values()
                    .contains("fork-credential-987654"));
            final var forkOnly = Set.of();
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
            final var configuration = ConfigurationLoader.load(
                    GithubBotConfiguration.class, name -> Optional.ofNullable(properties.get(name)));
            return new GithubSecretValuesContributor(configuration, new ConfigurationImpl(List.of()));
        }

        private static void check(final boolean condition) {
            if (!condition) {
                throw new AssertionError("Credential probe failed");
            }
        }
    }
}
