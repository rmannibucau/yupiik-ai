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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies YCA configuration-file secret harvesting: API keys ({@code *apiKey*} leaves) and the
 * credential part of known secret HTTP headers (from {@code yca.secrets.http.headerNames}), read from
 * BOTH the project-local {@code .yca/configuration.json} and the global configuration file - even when
 * a local file would shadow the global one in the resolved configuration.
 */
@ExtendWith(TestJsonMapper.class)
class YcaConfigSecretValuesContributorTest {

    @TempDir
    Path tmp;

    @Test
    void harvestsApiKeyLeavesAndSecretHeaderTokens(final JsonMapper mapper) throws Exception {
        final var local = tmp.resolve(".yca").resolve("configuration.json");
        Files.createDirectories(local.getParent());
        Files.writeString(local, """
                {
                  "yca": {
                    "provider": {
                      "apiKey": "project-api-key-secret",
                      "providers": [ {"id": "p", "apiKey": "provider-key-secret"} ]
                    },
                    "tools": { "enabled": true }
                  }
                }
                """);
        final var global = tmp.resolve("global-config.json");
        Files.writeString(global, """
                {
                  "yca": {
                    "provider": {
                      "dynamicHeaders": {
                        "Authorization": "Bearer global-bearer-token",
                        "X-Custom": "not-a-secret-header"
                      }
                    }
                  }
                }
                """);

        final var rawConfig = Configuration.of(Map.of(
                "yca.workingDir", tmp.toString(),
                "yca.configuration.json", global.toString(),
                "user.home", tmp.toString()));
        final var contributor =
                new YcaConfigSecretValuesContributor(TestConfigs.codingAgent(Map.of()), rawConfig, mapper);
        final var values = Set.copyOf(contributor.values());

        // apiKey leaves (both files)
        assertTrue(values.contains("project-api-key-secret"), "project apiKey: " + values);
        assertTrue(values.contains("provider-key-secret"), "provider apiKey: " + values);
        // secret header: only the credential after the scheme is harvested
        assertTrue(values.contains("global-bearer-token"), "Authorization token: " + values);
        // the global file is harvested even though a local file exists (reads both files)
        assertTrue(values.contains("global-bearer-token"), "global header harvested despite local file: " + values);
        // non-secret header value must NOT be harvested
        assertFalse(values.contains("not-a-secret-header"), "non-secret header must be ignored: " + values);
        // scheme prefix alone must not be a secret
        assertFalse(values.contains("Bearer"), "scheme prefix must not be a secret: " + values);
    }

    @Test
    void missingFilesAreSkipped(final JsonMapper mapper) throws Exception {
        final var rawConfig = Configuration.of(Map.of(
                "yca.workingDir", tmp.resolve("nope").toString(),
                "yca.configuration.json", tmp.resolve("missing.json").toString(),
                "user.home", tmp.resolve("home").toString()));
        final var contributor =
                new YcaConfigSecretValuesContributor(TestConfigs.codingAgent(Map.of()), rawConfig, mapper);
        assertTrue(contributor.values().isEmpty(), "no files -> no secrets: " + contributor.values());
    }

    @Test
    void explicitLocationsOverrideAutoComputation(final JsonMapper mapper) throws Exception {
        final var onlyFile = tmp.resolve("only.json");
        Files.writeString(onlyFile, """
                {"Authorization": "Bearer custom-location-token-value", "apiKey": "custom-api-key"}
                """);

        final var config = TestConfigs.codingAgent(Map.of("yca.secrets.ycaConfig.locations", onlyFile.toString()));
        // a rawConfiguration pointing elsewhere to prove the explicit locations win
        final var rawConfig = Configuration.of(Map.of(
                "yca.workingDir", tmp.resolve("ignored-workdir").toString(),
                "user.home", tmp.toString()));
        final var contributor = new YcaConfigSecretValuesContributor(config, rawConfig, mapper);
        final var values = Set.copyOf(contributor.values());

        assertTrue(values.contains("custom-location-token-value"), "Authorization token: " + values);
        assertTrue(values.contains("custom-api-key"), "apiKey: " + values);
    }
}
