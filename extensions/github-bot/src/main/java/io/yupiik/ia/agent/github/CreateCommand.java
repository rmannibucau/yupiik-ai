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
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@DefaultScoped
@Command(
        name = {"github", "bot", "create"},
        description = "Create a trusted operator task.")
public class CreateCommand implements Runnable {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Logger LOGGER = Logger.getLogger(CreateCommand.class.getName());
    private final Options options;
    private final GithubBotConfiguration conf;
    private final JsonMapper json;

    public CreateCommand(final Options options, final GithubBotConfiguration conf, final JsonMapper json) {
        this.options = options;
        this.conf = conf;
        this.json = json;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Exact owner/repository for manual creation.")
            String repository,

            @Property(defaultValue = "0", documentation = "Issue or pull request number for creation.")
            int number,

            @Property(defaultValue = "\"solve\"", documentation = "Task kind: solve, review or help.")
            String kind,

            @Property(defaultValue = "\"\"", documentation = "Manual request text (trusted operator input).")
            String request,

            @Property(
                    defaultValue = "false",
                    documentation = "Validate and preview without changing tasks or audit events.")
            boolean dryRun) {}

    @Override
    public void run() {
        final var store = new TaskStore(Path.of(conf.home()), json);
        if (!REPOSITORY_NAME.matcher(options.repository()).matches()
                || options.number() <= 0
                || !Set.of("solve", "review", "help").contains(options.kind())) {
            throw new IllegalArgumentException("Valid repository, positive number and task kind required");
        }
        final var document = new TaskDocument(
                null,
                options.repository(),
                options.number(),
                options.kind(),
                options.request(),
                Instant.now().toString(),
                "https://github.com/" + options.repository() + "/issues/" + options.number(),
                null,
                null,
                Map.of());
        if (options.dryRun()) {
            LOGGER.info(json.toString(document));
            return;
        }
        final var task = document.toMap();
        final String id = store.transaction(state -> state.enqueue("manual:" + UUID.randomUUID(), task));
        task.put("id", id);
        new BotAudit(Path.of(conf.home()), json).record("manual-created", task);
        LOGGER.info(id);
    }
}
