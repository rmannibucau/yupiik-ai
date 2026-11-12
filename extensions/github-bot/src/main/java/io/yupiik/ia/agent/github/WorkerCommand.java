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
package io.yupiik.ia.agent.github;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.file.Path;
import java.time.Clock;

@DefaultScoped
@Command(
        name = {"github", "bot", "worker"},
        description = "Execute isolated pending tasks.")
public class WorkerCommand implements Runnable {
    private final Options options;
    private final GithubBotConfiguration conf;
    private final JsonMapper json;
    private final ExecutorConfiguration execution;
    private final NotificationService notifications;

    public WorkerCommand(
            final Options options,
            final GithubBotConfiguration conf,
            final JsonMapper json,
            final ExecutorConfiguration execution,
            final NotificationService notifications) {
        this.options = options;
        this.conf = conf;
        this.json = json;
        this.execution = execution;
        this.notifications = notifications;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Optional task id; empty selects the next pending task.")
            String task,

            @Property(documentation = "Worker operation settings.")
            WorkerOptions worker) {}

    @Override
    public void run() {
        final var store = new TaskStore(Path.of(conf.home()), json);
        try (final var runtime = new GithubRuntime(conf, json, Clock.systemUTC())) {
            new Worker(conf, options.worker(), store, runtime.client(), json, execution).run(options.task());
            if (notifications != null) {
                final var failed = store.transaction(state -> {
                    final var selected = state.list().stream()
                            .filter(task -> "failed".equals(task.get("state"))
                                    && !Boolean.TRUE.equals(task.get("failureNotified")))
                            .toList();
                    for (final var task : selected) {
                        state.task(String.valueOf(task.get("id"))).put("failureNotified", true);
                    }
                    return selected;
                });
                for (final var task : failed) {
                    notifications.onTurnEnd(
                            String.valueOf(task.get("id")),
                            "GitHub bot task failed",
                            "",
                            "github-bot",
                            false,
                            "Task " + task.get("id") + " in " + task.get("repository")
                                    + " requires operator attention.");
                }
            }
        }
    }
}
