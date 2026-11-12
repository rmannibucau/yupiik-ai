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
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "delete"},
        description = "Delete an inactive task, retaining receipts.")
public class DeleteCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(DeleteCommand.class.getName());
    private final Options options;
    private final GithubBotConfiguration conf;
    private final JsonMapper json;

    public DeleteCommand(final Options options, final GithubBotConfiguration conf, final JsonMapper json) {
        this.options = options;
        this.conf = conf;
        this.json = json;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Inactive task id to delete.")
            String task,

            @Property(
                    defaultValue = "false",
                    documentation = "Validate and preview without changing tasks or audit events.")
            boolean dryRun) {}

    @Override
    public void run() {
        final var store = new TaskStore(Path.of(conf.home()), json);
        final var deleted = store.transaction(state -> {
            final var task = state.task(options.task());
            if (Set.of("running", "publishing").contains(String.valueOf(task.get("state")))) {
                throw new IllegalStateException("Cannot delete active or ambiguous publication task");
            }
            if (task.containsKey("attemptDirectory")) {
                throw new IllegalStateException("Cannot delete a task with retained attempt data");
            }
            if (!options.dryRun()) {
                state.tasks().remove(options.task());
            }
            return new LinkedHashMap<>(task);
        });
        if (options.dryRun()) {
            LOGGER.info(json.toString(TaskDocument.from(deleted)));
        } else {
            new BotAudit(Path.of(conf.home()), json).record("manual-deleted", deleted);
        }
    }
}
