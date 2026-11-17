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

import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackContextConfigurationTest {
    @Test
    void generatedDefaultsAreDenyByDefaultAndBounded() {
        final var config = new SlackContextConfiguration$FusionConfigurationFactory(Configuration.of(Map.of())).get();
        assertFalse(config.enabled());
        assertFalse(config.allowSwapBackedTmpfs());
        assertFalse(config.allowContainerLogs());
        assertTrue(config.resolveMentions());
        assertFalse(config.searchEnabled());
        assertEquals(List.of(), config.sourceChannels());
        assertEquals(30, config.lookbackDays());
        assertEquals(32, config.maxMembers());
        assertEquals(12, config.maxRequests());
        assertEquals(20, config.maxResults());
        assertEquals(131072, config.maxResponseBytes());
        assertEquals(20, config.timeoutSeconds());
        assertEquals(2, config.maxConcurrent());
        assertEquals(600, config.actionRetentionSeconds());
    }

    @Test
    void validatesEveryLimitAndAcceptsOnlyExactPublicChannelIds() {
        for (final var entry : Map.of(
                        "lookbackDays",
                        "0",
                        "maxMembers",
                        "0",
                        "maxRequests",
                        "0",
                        "maxResults",
                        "1001",
                        "maxResponseBytes",
                        "100",
                        "timeoutSeconds",
                        "0",
                        "maxConcurrent",
                        "0",
                        "actionRetentionSeconds",
                        "0")
                .entrySet()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackContextConfiguration$FusionConfigurationFactory(
                                    Configuration.of(Map.of("slack.context." + entry.getKey(), entry.getValue())))
                            .get(),
                    entry.getKey());
        }
        for (final var channel : List.of("G1", "D1", "C.*", "C1,C2", "c1", "C", "C1 ")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackContextConfiguration(
                            false,
                            true,
                            false,
                            List.of(channel),
                            30,
                            32,
                            12,
                            20,
                            131072,
                            20,
                            2,
                            600,
                            false,
                            new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                            false));
        }
    }

    @Test
    void sourceAllowlistIsAnImmutableSnapshotAndNullDeniesAll() {
        final var sources = new ArrayList<>(List.of("C1"));
        final var config = new SlackContextConfiguration(
                true,
                true,
                true,
                sources,
                30,
                32,
                12,
                20,
                131072,
                20,
                2,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
        sources.clear();
        assertEquals(List.of("C1"), config.sourceChannels());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.sourceChannels().clear());
        assertEquals(
                List.of(),
                new SlackContextConfiguration(
                                false,
                                true,
                                false,
                                null,
                                30,
                                32,
                                12,
                                20,
                                131072,
                                20,
                                2,
                                600,
                                false,
                                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                                false)
                        .sourceChannels());
    }

    @Test
    void containerLogsRequireExplicitOperatorConfiguration() {
        final var config = new SlackContextConfiguration$FusionConfigurationFactory(
                        Configuration.of(Map.of("slack.context.allowContainerLogs", "true")))
                .get();
        assertTrue(config.allowContainerLogs());
        assertFalse(config.allowSwapBackedTmpfs());
        assertFalse(config.enabled());
        assertFalse(config.searchEnabled());
    }

    @Test
    void swapBackedTmpfsRequiresExplicitOperatorConfiguration() {
        final var config = new SlackContextConfiguration$FusionConfigurationFactory(
                        Configuration.of(Map.of("slack.context.allowSwapBackedTmpfs", "true")))
                .get();
        assertTrue(config.allowSwapBackedTmpfs());
        assertFalse(config.enabled());
        assertFalse(config.searchEnabled());
    }
}
