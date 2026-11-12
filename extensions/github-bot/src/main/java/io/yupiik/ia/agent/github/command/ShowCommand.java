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
package io.yupiik.ia.agent.github.command;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"github", "bot", "show"},
        description = "Show one durable task.")
public class ShowCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ShowCommand.class.getName());
    private final Options options;
    private final JsonMapper json;
    private final TaskStore store;

    public ShowCommand(final Options options, final JsonMapper json, final TaskStore store) {
        this.options = options;
        this.json = json;
        this.store = store;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(defaultValue = "\"\"", documentation = "Task id to show.")
            String task) {}

    @Override
    public void run() {
        LOGGER.info(json.toString(
                store.transaction(state -> state.task(options.task()).document())));
    }
}
