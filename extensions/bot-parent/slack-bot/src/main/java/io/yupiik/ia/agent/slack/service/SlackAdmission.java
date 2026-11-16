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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.store.SlackStore;
import java.util.Map;
import java.util.Optional;

/** Authenticated transport is not authorization: exact trusted installation/channel/user policy is mandatory. */
@ApplicationScoped
public class SlackAdmission {
    private final SlackConfiguration configuration;
    private final SlackMentionParser parser;
    private final SlackStore store;

    public SlackAdmission(
            final SlackConfiguration configuration, final SlackMentionParser parser, final SlackStore store) {
        this.configuration = configuration;
        this.parser = parser;
        this.store = store;
    }

    public Optional<String> admit(final Map<String, Object> payload) {
        configuration.validateInstallation();
        if (!"event_callback".equals(payload.get("type"))
                || !configuration.workspace().equals(payload.get("team_id"))
                || !(payload.get("event_id") instanceof String eventId)
                || eventId.length() > 128
                || !eventId.matches("Ev[A-Za-z0-9]+")
                || !(payload.get("event") instanceof Map<?, ?> event)
                || !"app_mention".equals(event.get("type"))
                || event.get("bot_id") != null
                || event.get("subtype") != null
                || !(event.get("channel") instanceof String channel)
                || !(event.get("user") instanceof String user)
                || !(event.get("ts") instanceof String ts)
                || !configuration.channels().contains(channel)
                || !configuration.users().contains(user)
                || configuration.botUser().equals(user)
                || !configuration.allowSlackConnect()
                        && (!Boolean.FALSE.equals(payload.get("is_ext_shared_channel"))
                                || Boolean.TRUE.equals(event.get("is_ext_shared_channel")))) {
            return Optional.empty();
        }
        final var thread = event.get("thread_ts") instanceof String parent ? parent : ts;
        if (!thread.matches("[0-9]{1,20}\\.[0-9]{1,20}")) {
            return Optional.empty();
        }
        final var parsed = parser.parse(
                event.get("text") instanceof String text ? text : null,
                configuration.botUser(),
                configuration.maxInputChars());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        final var id = SlackStore.identity(configuration.workspace() + ":" + eventId);
        final var conversation =
                SlackStore.identity(configuration.workspace() + ":" + channel + ":" + thread + ":" + user);
        return store.transaction(tasks -> {
            if (tasks.stream().anyMatch(task -> task.id().equals(id))) {
                return Optional.of(id);
            }
            if (tasks.size() >= 10000
                    || tasks.stream().filter(task -> !task.terminal()).count() >= configuration.maxPending()
                    || tasks.stream()
                                    .filter(task ->
                                            !task.terminal() && task.requester().equals(user))
                                    .count()
                            >= configuration.maxPendingPerUser()) {
                return Optional.empty();
            }
            final var now = System.currentTimeMillis();
            tasks.add(new SlackTask(
                    id,
                    configuration.workspace(),
                    channel,
                    thread,
                    user,
                    parsed.orElseThrow(),
                    conversation,
                    "queued",
                    now,
                    now,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            return Optional.of(id);
        });
    }
}
