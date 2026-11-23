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
package io.yupiik.ia.agent.slack.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlackExecutorConfigurationTest {
    @Test
    void absentOptionalGatewaysStayAbsentAndKubernetesUsesUnprivilegedDefaults() {
        final var executor = configuration(Map.of());
        assertNull(executor.docker().egress());
        assertNull(executor.docker().services());
        assertEquals(10001L, executor.kubernetes().uid());
        assertEquals(10001L, executor.kubernetes().gid());
    }

    @Test
    void containerLogsRemainEnabledByDefaultAndCanBeExplicitlyDisabled() {
        assertFalse(configuration(Map.of()).docker().disableContainerLogs());
        assertFalse(configuration(Map.of("docker.disableContainerLogs", "false"))
                .docker()
                .disableContainerLogs());
        assertTrue(configuration(Map.of("docker.disableContainerLogs", "true"))
                .docker()
                .disableContainerLogs());
    }

    @Test
    void configuredEgressDefaultsToEnabled() {
        final var egress = configuration(Map.of("docker.egress.image", "trusted-egress"))
                .docker()
                .egress();
        assertNotNull(egress);
        assertTrue(egress.enabled());
    }

    @Test
    void servicesDependencyEgressDefaultsToEnabled() {
        final var egress = configuration(services()).docker().services().dependencyEgress();
        assertTrue(egress.enabled());
        assertEquals("trusted-services", egress.image());
        assertTrue(egress.allowedHosts().contains("repo.maven.apache.org"));
    }

    @Test
    void explicitFalseIsNotReplacedByTheEgressDefault() {
        final var egress = configuration(Map.of(
                        "docker.egress.image", "trusted-egress",
                        "docker.egress.enabled", "false"))
                .docker()
                .egress();
        assertNotNull(egress);
        assertFalse(egress.enabled());

        final var values = new HashMap<>(services());
        values.put("docker.services.dependencyEgress.enabled", "false");
        assertFalse(configuration(values).docker().services().dependencyEgress().enabled());
    }

    @Test
    void explicitRootIdentifiersAreNotReplacedByUnprivilegedDefaults() {
        final var kubernetes = configuration(Map.of("kubernetes.uid", "0", "kubernetes.gid", "0"))
                .kubernetes();
        assertEquals(0L, kubernetes.uid());
        assertEquals(0L, kubernetes.gid());
    }

    @Test
    void malformedIdentifiersStillFailBinding() {
        for (final var property : List.of("kubernetes.uid", "kubernetes.gid")) {
            for (final var value : List.of("not-a-number", "9223372036854775808")) {
                assertThrows(NumberFormatException.class, () -> configuration(Map.of(property, value)));
            }
        }
    }

    @Test
    void malformedServicesStillFailValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration(Map.of("docker.services.image", "trusted-services")));
        for (final var target : List.of("https://10.0.0.1:8080", "http://provider.example:8080")) {
            final var values = new HashMap<>(services());
            values.put("docker.services.providerTarget", target);
            assertThrows(IllegalArgumentException.class, () -> configuration(values));
        }
        final var conflicting = new HashMap<>(services());
        conflicting.put("docker.egress.image", "trusted-egress");
        assertThrows(IllegalArgumentException.class, () -> configuration(conflicting));
    }

    private ExecutorConfiguration configuration(final Map<String, String> values) {
        return ConfigurationLoader.load(
                        SlackExecutorConfiguration.class,
                        key -> Optional.ofNullable(values.get(key.substring("slack.executor.".length()))))
                .shared();
    }

    private Map<String, String> services() {
        return Map.of(
                "docker.services.image", "trusted-services",
                "docker.services.controlNetwork", "trusted-control",
                "docker.services.keyStore", "/private/services.p12",
                "docker.services.providerTarget", "http://10.0.0.1:8080/v1");
    }
}
