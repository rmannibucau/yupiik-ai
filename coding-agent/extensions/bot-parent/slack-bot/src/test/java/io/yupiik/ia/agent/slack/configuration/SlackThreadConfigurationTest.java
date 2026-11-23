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
import io.yupiik.ia.agent.core.configuration.ConfigurationLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackThreadConfigurationTest {
    @Test
    void nestedDefaultsAreDisabledAndIndependentOfSearch() {
        final var config = ConfigurationLoader.load(SlackContextConfiguration.class, Configuration.of(Map.of()));
        assertEquals(new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24), config.thread());
        assertFalse(config.enabled());
        assertFalse(config.searchEnabled());
        assertTrue(config.sourceChannels().isEmpty());
        final var explicit = ConfigurationLoader.load(
                SlackContextConfiguration.class,
                Configuration.of(Map.of(
                        "slack.context.thread.enabled", "true",
                        "slack.context.thread.channels", "C1",
                        "slack.context.thread.initialMessages", "10",
                        "slack.context.thread.maxMessages", "80",
                        "slack.context.thread.maxBytes", "32768",
                        "slack.context.thread.maxRequests", "2",
                        "slack.context.thread.lookbackHours", "12",
                        "slack.context.maxResults", "1000")));
        assertEquals(new SlackThreadConfiguration(true, List.of("C1"), 10, 80, 32768, 2, 12), explicit.thread());
        assertFalse(explicit.enabled(), "Thread policy must not enable parent context");
        assertFalse(explicit.searchEnabled(), "Thread policy must not enable search");
        assertEquals(1000, explicit.maxResults());
    }

    @Test
    void onlyExactPublicAllowlistIsAcceptedAndSnapshotIsImmutable() {
        for (final var channel : List.of("G1", "D1", "C.*", "C1,C2", "c1", "C", "C1 ")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SlackThreadConfiguration(true, List.of(channel), 20, 100, 65536, 3, 24));
        }
        final var channels = new ArrayList<>(List.of("C1"));
        final var config = new SlackThreadConfiguration(true, channels, 20, 100, 65536, 3, 24);
        channels.clear();
        assertEquals(List.of("C1"), config.channels());
        assertThrows(
                UnsupportedOperationException.class, () -> config.channels().clear());
        assertEquals(List.of(), new SlackThreadConfiguration(true, null, 20, 100, 65536, 3, 24).channels());
    }

    @Test
    void initialAndCumulativeLimitsAreSeparatelyBounded() {
        for (final var entry : Map.of(
                        "initialMessages", List.of("0", "21"),
                        "maxMessages", List.of("19", "1001"),
                        "maxBytes", List.of("1023", "16777217"),
                        "maxRequests", List.of("0", "1001"),
                        "lookbackHours", List.of("0", "8761"))
                .entrySet()) {
            for (final var value : entry.getValue()) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ConfigurationLoader.load(
                                SlackContextConfiguration.class,
                                Configuration.of(Map.of("slack.context.thread." + entry.getKey(), value))),
                        entry.getKey() + "=" + value);
            }
        }
        assertEquals(
                1000, new SlackThreadConfiguration(true, List.of("C1"), 20, 1000, 16777216, 1000, 8760).maxMessages());
    }

    @Test
    void nullNestedPolicyNormalizesToDisabledDefaults() {
        final var config = new SlackContextConfiguration(
                true, true, true, List.of("C1"), 30, 32, 12, 20, 131072, 20, 2, 600, false, null, false);
        assertEquals(new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24), config.thread());
    }
}
