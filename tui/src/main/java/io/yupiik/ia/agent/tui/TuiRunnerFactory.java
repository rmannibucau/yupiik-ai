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
    public TuiRunner create(final TuiCommand.TuiConf conf) throws Exception {
        return create(conf, false);
    }

    public TuiRunner create(final TuiCommand.TuiConf conf, final boolean hideCursor) throws Exception {
        return TuiRunner.create(TuiConfig.builder()
                .mouseCapture(true)
                // bracketed paste (ESC[?2004h): ctrl+shift+v / middle-click pastes arrive as a
                // single PasteEvent preserving newlines instead of a keystroke burst
                .bracketedPaste(true)
                .tickRate(Duration.ofMillis(100))
                .bindings(conf.bindings().toBindings())
                .hideCursor(hideCursor)
                .build());
    }
}
