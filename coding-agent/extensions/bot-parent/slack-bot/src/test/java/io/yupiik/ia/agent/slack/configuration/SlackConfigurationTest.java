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

class SlackConfigurationTest {
    @Test
    void generatedBindingKeepsChannelMemberAccessOptInAndValidatesRequesters() {
        final var defaults = ConfigurationLoader.load(SlackConfiguration.class, Configuration.of(Map.of()));
        assertFalse(defaults.allowChannelMembers());
        assertFalse(defaults.allowsRequester("U3"));
        final var restricted = ConfigurationLoader.load(
                SlackConfiguration.class, Configuration.of(Map.of("slack.botUser", "UBOT", "slack.users", "U1")));
        assertTrue(restricted.allowsRequester("U1"));
        assertFalse(restricted.allowsRequester("U3"));
        final var members = ConfigurationLoader.load(
                SlackConfiguration.class,
                Configuration.of(Map.of("slack.botUser", "UBOT", "slack.allowChannelMembers", "true")));
        assertTrue(members.allowChannelMembers());
        assertTrue(members.users().isEmpty());
        assertTrue(members.allowsRequester("U3"));
        assertTrue(members.allowsRequester("W3"));
        assertFalse(members.allowsRequester(null));
        for (final var user : List.of("", "UBOT", "B1", "U.*", "U", "u3", "U3 ", "U" + "A".repeat(64))) {
            assertFalse(members.allowsRequester(user), user);
        }
        final var disabled = ConfigurationLoader.load(
                SlackConfiguration.class, Configuration.of(Map.of("slack.allowChannelMembers", "false")));
        assertFalse(disabled.allowsRequester("U3"));
    }

    @Test
    void missingAllowlistsDenyByDefaultAndConfiguredListsAreImmutable() {
        final var empty = configuration("T1", "UBOT", null, null, 4, 2, 86400);
        assertEquals(List.of(), empty.channels());
        assertEquals(List.of(), empty.users());
        final var channels = new ArrayList<>(List.of("C1"));
        final var users = new ArrayList<>(List.of("U1"));
        final var configured = configuration("T1", "UBOT", channels, users, 4, 2, 86400);
        channels.clear();
        users.clear();
        assertEquals(List.of("C1"), configured.channels());
        assertEquals(List.of("U1"), configured.users());
        assertThrows(
                UnsupportedOperationException.class, () -> configured.users().add("U2"));
    }

    @Test
    void installationIdsLimitsAndRetentionAreStrictAndBounded() {
        assertDoesNotThrow(
                () -> configuration("T1", "UBOT", null, null, 4, 2, 86400).validateInstallation());
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration("workspace", "UBOT", null, null, 4, 2, 86400)
                        .validateInstallation());
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration("T1", "U.*", null, null, 4, 2, 86400).validateInstallation());
        assertThrows(
                IllegalArgumentException.class, () -> configuration("T1", "UBOT", List.of(".*"), null, 4, 2, 86400));
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration("T1", "UBOT", null, List.of("U" + "A".repeat(100000)), 4, 2, 86400));
        assertThrows(IllegalArgumentException.class, () -> configuration("T1", "UBOT", null, null, 0, 1, 86400));
        assertThrows(IllegalArgumentException.class, () -> configuration("T1", "UBOT", null, null, 4, 5, 86400));
        assertThrows(IllegalArgumentException.class, () -> configuration("T1", "UBOT", null, null, 4, 2, 86399));
    }

    @Test
    void trustedPromptAndPresentationBoundsAreCheckedBeforeCompilation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SlackPrompts("", "{{request}}", "output", "Answer", "Cancel", "Question", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SlackPrompts("policy", "x".repeat(32001), "output", "Answer", "Cancel", "Question", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SlackPrompts("policy", "{{request}}", "output", "x".repeat(25), "Cancel", "Question", ""));
    }

    @Test
    void contextGuidanceBindingSupportsReplacementAndBlankWithoutChangingOtherPrompts() {
        final var defaults = ConfigurationLoader.load(SlackPrompts.class, Configuration.of(Map.of()));
        assertTrue(defaults.contextGuidance().contains("slack_context tool exists"));
        for (final var guidance : List.of("Only use cited evidence. {{request}}", "", " ", "x".repeat(32000))) {
            final var configured = ConfigurationLoader.load(
                    SlackPrompts.class, Configuration.of(Map.of("slack.prompts.contextGuidance", guidance)));
            assertEquals(guidance, configured.contextGuidance());
            assertEquals(defaults.policy(), configured.policy());
            assertEquals(defaults.main(), configured.main());
            assertEquals(defaults.output(), configured.output());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigurationLoader.load(
                        SlackPrompts.class,
                        Configuration.of(Map.of("slack.prompts.contextGuidance", "x".repeat(32001)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SlackPrompts("policy", "{{request}}", "output", "Answer", "Cancel", "Question", null));
    }

    private SlackConfiguration configuration(
            final String workspace,
            final String bot,
            final List<String> channels,
            final List<String> users,
            final int maximum,
            final int perUser,
            final int retention) {
        return new SlackConfiguration(
                workspace,
                bot,
                channels,
                users,
                false,
                false,
                "SLACK_APP_TOKEN",
                "SLACK_BOT_TOKEN",
                "private-state",
                "agent.json",
                maximum,
                perUser,
                16000,
                12000,
                60,
                retention);
    }
}
