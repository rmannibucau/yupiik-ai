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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.framework.api.container.configuration.source.EnvironmentSource;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProviderBudgetConfigurationTest {
    @Test
    void defaultsAreAbsentAndDottedPropertiesPreserveBlankAndNoncanonicalValues() {
        assertEquals(new ProviderBudgetConfiguration(null, null, null), configuration(Map.of()));
        assertEquals(
                new ProviderBudgetConfiguration("/trusted/budget", "account", "2"),
                configuration(Map.of(
                        "yca.provider.budget.root", "/trusted/budget",
                        "yca.provider.budget.id", "account",
                        "yca.provider.budget.slots", "2")));
        for (final var slots : List.of("", "01", "invalid")) {
            final var configuration = configuration(Map.of("yca.provider.budget.slots", slots));
            assertEquals(slots, configuration.slots());
            assertThrows(IllegalArgumentException.class, () -> ProviderRequestBudget.fromConfiguration(configuration));
        }
    }

    @Test
    void jsonRoundTripPreservesTrustedSettingsAndAbsentFields() {
        try (final var json = new JsonMapperImpl(
                List.of(new ProviderBudgetConfiguration$FusionJsonCodec()), key -> Optional.empty())) {
            final var expected = new ProviderBudgetConfiguration("/trusted/budget", "account", "2");
            assertEquals(expected, json.fromString(ProviderBudgetConfiguration.class, json.toString(expected)));
            assertEquals(
                    new ProviderBudgetConfiguration(null, null, null),
                    json.fromString(ProviderBudgetConfiguration.class, "{}"));
            assertEquals(
                    "01",
                    json.fromString(ProviderBudgetConfiguration.class, "{\"slots\":\"01\"}")
                            .slots());
        }
    }

    @Test
    void privateEnvironmentHandoffBindsToDottedProperties() throws Exception {
        final var classpath = Path.of(getClass()
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + File.pathSeparator
                + Path.of(ProviderBudgetConfiguration.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                + File.pathSeparator
                + Path.of(Configuration.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
        final var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                Child.class.getName());
        builder.environment()
                .putAll(Map.of(
                        ProviderRequestBudget.ROOT, "/trusted/budget",
                        ProviderRequestBudget.ID, "account",
                        ProviderRequestBudget.SLOTS, "2"));
        builder.redirectErrorStream(true);
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertEquals("/trusted/budget|account|2", output.strip());
        } finally {
            process.destroyForcibly();
        }
    }

    private ProviderBudgetConfiguration configuration(final Map<String, String> settings) {
        return new ProviderBudgetConfiguration$FusionConfigurationFactory(
                        new ConfigurationImpl(List.of(new MapConfigSource(settings))))
                .get();
    }

    public static final class Child {
        public static void main(final String[] args) {
            final var configuration = new ProviderBudgetConfiguration$FusionConfigurationFactory(
                            new ConfigurationImpl(List.of(new EnvironmentSource())))
                    .get();
            System.out.println(configuration.root() + "|" + configuration.id() + "|" + configuration.slots());
        }
    }
}
