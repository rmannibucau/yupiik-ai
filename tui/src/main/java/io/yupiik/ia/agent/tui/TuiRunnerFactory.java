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

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.time.Duration;

/**
 * Creates the {@link TuiRunner} used by the TUI command. Isolated in a bean so
 * tests can substitute a headless runner backed by an in-memory terminal.
 */
@ApplicationScoped
public class TuiRunnerFactory {
    /**
     * Any-motion mouse tracking ({@code ESC[?1003h}), enabled here so the tab-bar hover
     * tooltip receives MOVE events. TamboUI's own mouse capture only uses
     * {@code ?1000h}/{@code ?1002h}/{@code ?1015h}/{@code ?1006h}, so it has no idea
     * {@code ?1003h} is on and never disables it - without a matching {@link #MOUSE_MOTION_DISABLE}
     * the terminal keeps reporting every mouse move after the TUI exits and the shell
     * prints the SGR reports ({@code ESC[<35;5;12M}) as garbage.
     */
    static final String MOUSE_MOTION_ENABLE = "\u001b[?1003h";

    static final String MOUSE_MOTION_DISABLE = "\u001b[?1003l";

    public TuiRunner create(final TuiCommand.TuiConf conf) throws Exception {
        return create(conf, false);
    }

    public TuiRunner create(final TuiCommand.TuiConf conf, final boolean hideCursor) throws Exception {
        final var runner = TuiRunner.create(TuiConfig.builder()
                .mouseCapture(true)
                // bracketed paste (ESC[?2004h): ctrl+shift+v / middle-click pastes arrive as a
                // single PasteEvent preserving newlines instead of a keystroke burst
                .bracketedPaste(true)
                .tickRate(Duration.ofMillis(100))
                // the event loop only wakes on real events after the idle poll expires;
                // a longer timeout cuts idle wakeups without hurting input latency (events
                // surface instantly through the non-blocking queue drain)
                .pollTimeout(Duration.ofMillis(100))
                .bindings(conf.bindings().toBindings())
                .hideCursor(hideCursor)
                .build());
        // tamboui only enables ?1000h/?1002h (button/press tracking): motion (MOVE) events
        // are what drive the tab-bar hover tooltip, so additionally enable ?1003h
        // (any-motion tracking). Terminals without it simply never report MOVE events.
        // The matching ?1003l is sent by TuiCommand when the TUI exits (clean and abrupt
        // paths), because tamboui's own cleanup() only disables the modes it enabled.
        runner.backend().writeRaw(MOUSE_MOTION_ENABLE);
        runner.backend().flush();
        return runner;
    }
}
