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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the {@link SecretNames} name classification and the process-environment vs. env-file
 * harvesting split introduced to stop the shell working-directory variables ({@code PWD}/{@code
 * OLDPWD}) from being harvested as secrets.
 */
class SecretNamesTest {

    @Test
    void knownSafeWorkingDirectoriesAreStillSecretIshButNeverHarvested() {
        final var names = names(TestConfigs.codingAgent(Map.of()));
        // PWD/OLDPWD still match the PWD keyword (so they are "secret-ish" by name rules) ...
        assertTrue(names.isSecretName("PWD"));
        assertTrue(names.isSecretName("OLDPWD"));
        // ... but they are on the default known-safe allowlist, so the process-env harvest skips them
        assertTrue(names.isKnownSafeName("PWD"));
        assertTrue(names.isKnownSafeName("OLDPWD"));
        // the allowlist match is exact: a lower-cased name is not "known safe", though it still
        // matches the keyword rule
        assertFalse(names.isKnownSafeName("pwd"));
        assertTrue(names.isSecretName("pwd"));
        assertFalse(names.isKnownSafeName("Pwd"));
    }

    @Test
    void realCredentialNamesAreSecretsAndNotKnownSafe() {
        final var names = names(TestConfigs.codingAgent(Map.of()));
        for (final var secret : List.of(
                "DB_PASSWORD",
                "PGPASSWORD",
                "MY_APP_API_TOKEN",
                "CLIENT_SECRET",
                "ACCESS_TOKEN",
                "HTTP_AUTHORIZATION")) {
            assertTrue(names.isSecretName(secret), secret + " must classify as secret");
            assertFalse(names.isKnownSafeName(secret), secret + " must not be known-safe");
        }
        assertFalse(names.isSecretName("HOME"));
        assertFalse(names.isSecretName("USER"));
    }

    @Test
    void nullConfigIsAnInertMatcher() {
        // no config at all -> nothing is classified, never a NPE
        final var names = new SecretNames((io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration) null);
        assertFalse(names.isKnownSafeName("PWD"));
        assertFalse(names.isSecretName("DB_PASSWORD"));
        assertFalse(names.isSecretName("HOME"));
    }

    @Test
    void configCanOverrideKeywordsAndEmptyTheSafeList() {
        // custom keyword set (comma-separated single key), and an explicitly empty knownSafeValues
        // (present-but-blank key -> empty list) so PWD is no longer safe.
        final var names = names(TestConfigs.codingAgent(Map.of(
                "yca.secrets.environment.keywords", "MYTOK",
                "yca.secrets.environment.knownSafeValues", "")));
        assertTrue(names.isSecretName("MY_APP_MYTOK_XYZ"));
        assertFalse(names.isSecretName("PWD")); // PWD keyword no longer configured
        assertFalse(names.isKnownSafeName("PWD")); // empty allowlist -> nothing is safe
        assertFalse(names.isKnownSafeName("OLDPWD"));
    }

    @Test
    void envFileStillHarvestsPwdWhileProcessEnvSkipsIt(@TempDir final Path tmp) throws IOException {
        // the split: a PWD=path line in an env file is harvestable, but the process-environment
        // path would skip it (isKnownSafeName) - proving .env files are not treated like the shell.
        final var envFile = tmp.resolve(".env");
        Files.writeString(envFile, "PWD=/some/folder/that/is/not/a/secret\n" + "DB_PASSWORD=sup3rs3cret\n");

        final var config = TestConfigs.codingAgent(Map.of("yca.secrets.envFiles", envFile.toString()));
        final var names = names(config);
        // process-env classification: PWD is known-safe so the process env path would drop it ...
        assertTrue(names.isKnownSafeName("PWD"));
        // ... yet the env-file harvest still turns the PWD=... VALUE into a harvested secret
        final Collection<String> values = new EnvFileSecretValuesContributor(config, names).values();
        // env-file harvest always applies the keyword rules: the PWD=... folder path IS harvested
        assertTrue(values.contains("/some/folder/that/is/not/a/secret"), "env file must harvest PWD value");
        // and the real secret on the DB_PASSWORD line is harvested too (2 lines -> 2 values)
        assertEquals(2, values.size());
        assertTrue(values.stream().anyMatch(v -> !v.startsWith("/")), "env file must still harvest a real secret");
    }

    private static SecretNames names(final io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration config) {
        return new SecretNames(config);
    }
}
