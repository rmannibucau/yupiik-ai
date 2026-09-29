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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import java.util.List;

/**
 * CLI-specific commands, contributed on top of the shared domain sets.
 */
@ApplicationScoped
@Order(2000)
public class CliCommands implements SlashCommandSet {
    @Override
    public String title() {
        return "CLI commands";
    }

    private final Prompt prompt;

    public CliCommands(final Prompt prompt) {
        this.prompt = prompt;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(SlashCommand.of(
                new SlashCommandSpec("/shortcuts", null, "show the keyboard shortcuts", SlashGroup.OTHER),
                SlashAction.sync((arg, env) -> ShellOutcome.transcript(shortcuts()))));
    }

    private String shortcuts() {
        final var sb = new StringBuilder("## keys\n\n");
        if (prompt.editing()) {
            sb.append("""
                    - `Tab` complete slash commands and their arguments
                    - `Up/Down` browse the in-memory history
                    - `Left/Right` move by one character; `Delete` removes the next character
                    - `Alt-b`/`Alt-f` move back/forward by one word
                    - `Alt-Backspace`, `Ctrl-W` delete the previous word
                    - `Ctrl-A`/`Ctrl-E` go to the beginning/end of the line
                    - `Ctrl-U`/`Ctrl-K` delete to the beginning/end of the line
                    - `Ctrl-L` clear the screen
                    """);
        }
        sb.append("""
                - `Ctrl-C` open the interrupt menu (cancel the current turn / exit); pressed twice when idle (within 2s) it quits
                - `Ctrl-D` exit
                """);
        return sb.toString();
    }
}
