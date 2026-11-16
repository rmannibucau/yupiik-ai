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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.nio.file.Path;
import java.util.List;

/** Trusted controller configuration. Tokens never enter task documents or attempts. */
@RootConfiguration("slack")
public record SlackConfiguration(
        @Property(defaultValue = "\"\"", documentation = "Single allowed workspace ID.")
        String workspace,

        @Property(defaultValue = "\"\"", documentation = "Bot user ID, not app ID or display name.")
        String botUser,

        @Property(documentation = "Exact allowed channel IDs; empty denies all.")
        List<String> channels,

        @Property(documentation = "Exact allowed requester IDs; empty denies all.")
        List<String> users,

        @Property(defaultValue = "false", documentation = "Explicitly authorize external/shared channel audiences.")
        boolean allowSlackConnect,

        @Property(
                defaultValue = "\"SLACK_APP_TOKEN\"",
                documentation = "Environment variable holding the xapp Socket Mode token.")
        String appTokenEnv,

        @Property(
                defaultValue = "\"SLACK_BOT_TOKEN\"",
                documentation = "Environment variable holding the xoxb API token.")
        String botTokenEnv,

        @Property(
                defaultValue = "\".yca-slack\"",
                documentation = "Private controller state root; never mount in agents.")
        String home,

        @Property(defaultValue = "\"\"", documentation = "Trusted agent JSON configuration path.")
        String agentConfiguration,

        @Property(defaultValue = "100", documentation = "Maximum durable nonterminal tasks.")
        int maxPending,

        @Property(defaultValue = "4", documentation = "Maximum nonterminal tasks per requester.")
        int maxPendingPerUser,

        @Property(defaultValue = "16000", documentation = "Maximum mention input characters.")
        int maxInputChars,

        @Property(defaultValue = "12000", documentation = "Maximum structured answer characters.")
        int maxAnswerChars,

        @Property(defaultValue = "600", documentation = "Maximum ask_user wait seconds, retaining worker slot.")
        int questionTimeoutSeconds,

        @Property(defaultValue = "2592000", documentation = "Terminal task retention seconds.")
        int retentionSeconds) {
    public SlackConfiguration {
        channels = channels == null ? List.of() : List.copyOf(channels);
        users = users == null ? List.of() : List.copyOf(users);
        if (maxPending < 1
                || maxPending > 10000
                || maxPendingPerUser < 1
                || maxPendingPerUser > maxPending
                || maxInputChars < 1
                || maxInputChars > 100000
                || maxAnswerChars < 1
                || maxAnswerChars > 30000
                || questionTimeoutSeconds < 1
                || questionTimeoutSeconds > 3600
                || retentionSeconds < 86400) {
            throw new IllegalArgumentException("Invalid Slack limits");
        }
        if (home == null
                || home.isBlank()
                || appTokenEnv == null
                || botTokenEnv == null
                || !appTokenEnv.matches("[A-Z_][A-Z_0-9]{0,127}")
                || !botTokenEnv.matches("[A-Z_][A-Z_0-9]{0,127}")) {
            throw new IllegalArgumentException("Invalid Slack state or token environment configuration");
        }
        for (final var id : channels) {
            if (!id.matches("[CG][A-Z0-9]{1,63}")) {
                throw new IllegalArgumentException("Invalid allowed channel ID");
            }
        }
        for (final var id : users) {
            if (!id.matches("[UW][A-Z0-9]{1,63}")) {
                throw new IllegalArgumentException("Invalid allowed user ID");
            }
        }
    }

    public Path stateRoot() {
        return Path.of(home).toAbsolutePath().normalize();
    }

    public void validateInstallation() {
        if (workspace == null
                || !workspace.matches("T[A-Z0-9]{1,63}")
                || botUser == null
                || !botUser.matches("[UW][A-Z0-9]{1,63}")) {
            throw new IllegalArgumentException("Configure exact slack.workspace and slack.botUser IDs");
        }
    }
}
