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
 * Verifies cloud credential-file secret harvesting: AWS INI (aws_secret_access_key, aws_session_token)
 * and GCP ADC JSON (client_secret, refresh_token), harvesting only static long-lived secret keys and
 * skipping non-secret keys and an empty Azure location list.
 */
@ExtendWith(TestJsonMapper.class)
class CloudCredentialsSecretValuesContributorTest {

    private static final String AWS_PER_PROVIDER_VALUE = "rg21kg8g-jxISFl-R9k-K7oQUNtS-K1Nwg";

    @TempDir
    Path tmp;

    @Test
    void awsIniHarvestsSecretKeysOnly(final JsonMapper mapper) throws Exception {
        final var aws = tmp.resolve("credentials");
        Files.writeString(aws, """
                [default]
                aws_access_key_id = AKIAEXAMPLE123
                aws_secret_access_key = aws-secret-access-key-value
                aws_session_token = aws-session-token-value
                region = us-east-1
                """);

        final var contributor = new CloudCredentialsSecretValuesContributor(
                TestConfigs.codingAgent(Map.of(
                        "yca.secrets.cloudCredentials.aws.locations", aws.toString(),
                        // gcp/az left unset -> defaults (missing) generate nothing
                        "yca.secrets.cloudCredentials.gcp.locations", "",
                        "yca.secrets.cloudCredentials.az.locations", "")),
                mapper);
        final var values = Set.copyOf(contributor.values());

        assertTrue(values.contains("aws-secret-access-key-value"), "secret_access_key: " + values);
        assertTrue(values.contains("aws-session-token-value"), "session_token: " + values);
        // the public access key id and harmless keys are not secrets
        assertFalse(values.contains("AKIAEXAMPLE123"), "access key id must not be a secret: " + values);
        assertFalse(values.contains("us-east-1"), "region must not be a secret: " + values);
    }

    @Test
    void gcpJsonHarvestsSecretKeysOnly(final JsonMapper mapper) throws Exception {
        final var gcp = tmp.resolve("application_default_credentials.json");
        Files.writeString(gcp, """
                {
                  "type": "authorized_user",
                  "client_id": "1234567890.apps.googleusercontent.com",
                  "client_secret": "gcp-client-secret-value",
                  "refresh_token": "gcp-refresh-token-value",
                  "project_id": "my-project"
                }
                """);

        final var contributor = new CloudCredentialsSecretValuesContributor(
                TestConfigs.codingAgent(Map.of(
                        "yca.secrets.cloudCredentials.aws.locations", "",
                        "yca.secrets.cloudCredentials.az.locations", "",
                        "yca.secrets.cloudCredentials.gcp.locations", gcp.toString())),
                mapper);
        final var values = Set.copyOf(contributor.values());

        assertTrue(values.contains("gcp-client-secret-value"), "client_secret: " + values);
        assertTrue(values.contains("gcp-refresh-token-value"), "refresh_token: " + values);
        // non-secret fields are not harvested
        assertFalse(values.contains("authorized_user"), "type must not be a secret: " + values);
        assertFalse(
                values.contains("1234567890.apps.googleusercontent.com"), "client_id must not be a secret: " + values);
        assertFalse(values.contains("my-project"), "project_id must not be a secret: " + values);
    }

    @Test
    void missingFilesAndEmptyAzureListAreSkipped(final JsonMapper mapper) {
        final var contributor = new CloudCredentialsSecretValuesContributor(
                TestConfigs.codingAgent(Map.of(
                        "yca.secrets.cloudCredentials.aws.locations",
                                tmp.resolve("missing-aws").toString(),
                        "yca.secrets.cloudCredentials.gcp.locations",
                                tmp.resolve("missing-gcp").toString(),
                        // az left empty -> default empty list -> nothing scanned
                        "yca.secrets.cloudCredentials.az.locations", "")),
                mapper);
        assertTrue(contributor.values().isEmpty(), "missing files + empty az -> no secrets: " + contributor.values());
    }

    @Test
    void perProviderLocationsOverride(final JsonMapper mapper) throws Exception {
        // only aws.locations configured; gcp and az fall back to defaults that do not exist here
        final var aws = tmp.resolve("override.ini");
        Files.writeString(aws, "aws_secret_access_key=" + AWS_PER_PROVIDER_VALUE + "\n");
        final var contributor = new CloudCredentialsSecretValuesContributor(
                TestConfigs.codingAgent(Map.of(
                        "yca.secrets.cloudCredentials.aws.locations", aws.toString(),
                        "yca.secrets.cloudCredentials.gcp.locations", "",
                        "yca.secrets.cloudCredentials.az.locations", "")),
                mapper);
        final var values = Set.copyOf(contributor.values());
        assertTrue(values.contains(AWS_PER_PROVIDER_VALUE), "per-provider aws override: " + values);
    }
}
