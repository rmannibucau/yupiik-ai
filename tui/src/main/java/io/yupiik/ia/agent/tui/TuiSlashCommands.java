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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.slash.SlashAction;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import java.util.List;

/**
 * TUI-contributed slash commands: the TUI-only theme switch and the TUI key
 * cheatsheet. Registered into the shared {@link SlashRegistry} like any other
 * {@link SlashCommandSet} bean.
 */
@ApplicationScoped
@Order(2000)
public class TuiSlashCommands implements SlashCommandSet {
    private static final String SHORTCUTS = KeymapCatalog.markdown();

    private volatile ThemeSwitcher themeSwitcher;

    /** Implemented by the TUI command once the widget stack is up. */
    public interface ThemeSwitcher {
        ShellOutcome switchTheme(String arg);
    }

    public void bind(final ThemeSwitcher switcher) {
        this.themeSwitcher = switcher;
    }

    @Override
    public String title() {
        return "TUI commands";
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/theme",
                                "(light|dark)",
                                "switch between light and dark themes (persisted)",
                                SlashGroup.CONFIG),
                        themeAction()),
                SlashCommand.of(
                        new SlashCommandSpec("/shortcuts", null, "show the keyboard shortcuts", SlashGroup.OTHER),
                        SlashAction.sync((arg, env) -> ShellOutcome.transcript("\n\n" + SHORTCUTS))));
    }

    private SlashAction themeAction() {
        return (arg, env) -> {
            final var switcher = themeSwitcher;
            return switcher == null
                    ? java.util.concurrent.CompletableFuture.completedStage(
                            ShellOutcome.status("theme is not available yet"))
                    : java.util.concurrent.CompletableFuture.completedStage(switcher.switchTheme(arg));
        };
    }
}
