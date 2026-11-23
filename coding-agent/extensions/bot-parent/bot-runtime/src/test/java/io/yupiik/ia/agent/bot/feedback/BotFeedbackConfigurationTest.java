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
package io.yupiik.ia.agent.bot.feedback;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.core.configuration.ConfigurationLoader;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BotFeedbackConfigurationTest {
    @Test
    void defaultsDisableFeedbackWithTenMinuteTimeoutAndDefaultMailbox() {
        final var configuration = configuration(Map.of());
        assertFalse(configuration.enabled());
        assertEquals(600, configuration.timeoutSeconds());
        assertNull(configuration.directory());
    }

    @Test
    void bindsExistingPropertyNames() {
        assertEquals(
                new BotFeedbackConfiguration(true, 42, "/custom/feedback"),
                configuration(Map.of(
                        "yca.bot.feedback.enabled", "true",
                        "yca.bot.feedback.timeoutSeconds", "42",
                        "yca.bot.feedback.directory", "/custom/feedback")));
    }

    @Test
    void rejectsOutOfRangeTimeoutsEvenWhenDisabled() {
        for (final var enabled : new boolean[] {false, true}) {
            for (final var timeout : new int[] {Integer.MIN_VALUE, -1, 0, 3601, Integer.MAX_VALUE}) {
                final var error = assertThrows(
                        IllegalArgumentException.class, () -> new BotFeedbackConfiguration(enabled, timeout, null));
                assertEquals(
                        "yca.bot.feedback.timeoutSeconds must be between 1 and 3600 inclusive", error.getMessage());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> configuration(Map.of(
                                "yca.bot.feedback.enabled", Boolean.toString(enabled),
                                "yca.bot.feedback.timeoutSeconds", Integer.toString(timeout))));
            }
        }
    }

    @Test
    void acceptsInclusiveTimeoutBounds() {
        for (final var timeout : new int[] {1, 3600}) {
            assertEquals(timeout, new BotFeedbackConfiguration(true, timeout, null).timeoutSeconds());
            assertEquals(
                    timeout,
                    configuration(Map.of("yca.bot.feedback.timeoutSeconds", Integer.toString(timeout)))
                            .timeoutSeconds());
        }
    }

    @Test
    void rejectsNonnumericTimeoutEvenWhenDisabled() {
        for (final var enabled : new String[] {"false", "true"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> configuration(
                            Map.of("yca.bot.feedback.enabled", enabled, "yca.bot.feedback.timeoutSeconds", "garbage")));
        }
    }

    @Test
    void jsonRoundTripAndValidation() {
        try (final var json = JsonMappers.create(key -> Optional.empty())) {
            final var configuration = new BotFeedbackConfiguration(true, 42, "/custom/feedback");
            assertEquals(configuration, json.fromString(BotFeedbackConfiguration.class, json.toString(configuration)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> json.fromString(BotFeedbackConfiguration.class, "{\"enabled\":false,\"timeoutSeconds\":0}"));
        }
    }

    private static BotFeedbackConfiguration configuration(final Map<String, String> values) {
        return ConfigurationLoader.load(BotFeedbackConfiguration.class, key -> Optional.ofNullable(values.get(key)));
    }
}
