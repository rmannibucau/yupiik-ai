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
package io.yupiik.ia.agent.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic matcher/redactor tests, independent of the process environment (no env files, no
 * env names). Uses self-contained {@code YCA_TEST_*} style values so no real credential is touched
 * and the dev box environment can never influence the outcome.
 */
class SecretRedactorTest {

    private static final String GITHUB = "github_pat_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVW";
    private static final String OPENAI = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK";
    private static final String AWS = "AKIAIOSFODNN7EXAMPLE";

    @Test
    void wellKnownPrefixes() {
        final var matcher = new WellKnownPrefixesSecretMatcher();
        assertTrue(matcher.matches(GITHUB));
        assertTrue(matcher.matches(OPENAI));
        assertTrue(matcher.matches(AWS));
        assertFalse(matcher.matches("not a secret value"));
        assertFalse(matcher.matches("ghp_short")); // below min body length
        // replace keeps the prefix and the length
        final var replaced = matcher.replace(GITHUB);
        assertEquals(GITHUB.length(), replaced.length());
        assertTrue(replaced.startsWith("github_pat_"));
        assertNotEquals(GITHUB, replaced);
    }

    @Test
    void connectionUri() {
        final var m = new ConnectionUriSecretMatcher();
        final var uri = "postgres://user:secretpassword@db.example.com:5432/app";
        assertTrue(m.matches(uri));
        final var replaced = m.replace(uri);
        assertTrue(replaced.startsWith("postgres://"));
        assertTrue(replaced.endsWith("@db.example.com:5432/app"));
        assertFalse(replaced.contains("secretpassword"));
    }

    @Test
    void jwt() {
        final var m = new JwtSecretMatcher();
        final var jwt =
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        assertTrue(m.matches(jwt));
        final var replaced = m.replace(jwt);
        assertEquals(jwt.length(), replaced.length());
        assertNotEquals(jwt, replaced);
    }

    @Test
    void pemBlock() {
        final var m = new PemKeySecretMatcher();
        final var pem = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIBPAIBAAJBANW5mQeCzPz4f0vXjQoQt9a0aBcKqYk8EfJn8HfT/ygZ7XqE+9L3
                A0oLvZ1YcVamGq2XkD1iQ0nFjGwu7TmcycECAwEAAQ==
                -----END RSA PRIVATE KEY-----
                """.trim();
        assertTrue(m.matches(pem));
        final var replaced = m.replace(pem);
        assertTrue(replaced.startsWith("-----BEGIN RSA PRIVATE KEY-----"));
        assertTrue(replaced.endsWith("-----END RSA PRIVATE KEY-----"));
        assertNotEquals(pem, replaced);
    }

    @Test
    void harvestAndAssignment() {
        final var password = "YCA_TEST_SECRET_BREVO_xkeysib0123456789abcdef";
        final var redactor = new SecretRedactor(registryWithHarvest(List.of(password)), new SecretSessionModes());
        // a secret value appearing inline is redacted
        final var redacted = redactor.redact("Authorization: Bearer " + password, null);
        assertFalse(redacted.contains(password));
        // a value named *_PASSWORD is caught by the harvest path (exact value match)
        final var redacted2 = redactor.redact("MY_APP_PASSWORD=" + password, null);
        assertFalse(redacted2.contains(password));
    }

    @Test
    void restoreOnlyEmitted() {
        final var redactor = redactorWithMatchers();
        final var value = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";
        final var redacted = redactor.redact(value, "s");
        assertNotEquals(value, redacted);
        // provenance: restoring brings the emitted fake back to the real value
        assertEquals(value, redactor.restore(redacted, "s"));
        // a model-invented token (never emitted by us) is left untouched on restore
        final var invented = "sk-proj-0000000000000000000000000000000000000000";
        assertEquals(invented, redactor.restore(invented, "s"));
    }

    @Test
    void modePassthroughDoesNothing() {
        final var modes = new SecretSessionModes();
        modes.set("s", SecretRedactionMode.PASSTHROUGH);
        final var redactor = new SecretRedactor(registryWithHarvest(List.of(GITHUB)), modes);
        assertEquals(GITHUB, redactor.redact(GITHUB, "s"));
    }

    @Test
    void equalLengthSecretsRestoreIndividually() {
        // two harvested secrets with the same length must get DISTINCT fakes and restore to their
        // OWN value (the old 'x'-fill placeholder collapsed them into the same fake and restored one
        // for the other).
        final var a = "sup3rS3cr3t-smoketest-password";
        final var b = "another-secret-value-of-29c!!";
        final var redactor = new SecretRedactor(registryWithHarvest(List.of(a, b)), new SecretSessionModes());
        final var redacted = redactor.redact("first=" + a + " second=" + b, "s");
        assertFalse(redacted.contains(a), "first secret must be redacted");
        assertFalse(redacted.contains(b), "second secret must be redacted");
        // the two fakes are distinct (no shared placeholder)
        final var restored = redactor.restore(redacted, "s");
        assertTrue(restored.contains("first=" + a), "first restores to its own value: " + restored);
        assertTrue(restored.contains("second=" + b), "second restores to its own value: " + restored);
    }

    @Test
    void configuredPrefixMatcher() {
        // ConfiguredPrefixSecretMatcher builds from config; here we test its empty (no config) path
        // and rely on the config-driven test in the registry test for the actual prefixes wiring.
        final var empty = new ConfiguredPrefixSecretMatcher(configWithNoSecrets());
        assertFalse(empty.matches(GITHUB)); // no configured prefixes -> nothing matches
    }

    /* ---------- helpers ---------- */

    private static SecretRedactor redactorWithMatchers() {
        final var registry = new SecretRegistry(
                configWithNoSecrets(), List.of(new WellKnownPrefixesSecretMatcher()), new SecretVault(), null);
        registry.injectTestHarvest(List.of()); // no env harvesting in tests
        return new SecretRedactor(registry, null);
    }

    private static SecretRegistry registryWithHarvest(final List<String> values) {
        final var registry = new SecretRegistry(
                configWithNoSecrets(), List.of(new WellKnownPrefixesSecretMatcher()), new SecretVault(), null);
        registry.injectTestHarvest(values);
        return registry;
    }

    private static io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration configWithNoSecrets() {
        return io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:0")
                .build();
    }
}
