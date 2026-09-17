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
package io.yupiik.ia.agent.tui.prompt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import java.io.IOException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

/**
 * JLine backed {@link Prompt} giving the plain {@code cli} REPL full line
 * editing: in-memory history (Up/Down), word motion (Alt-b/Alt-f),
 * word deletion (Alt-Backspace, Ctrl-W), line motion/deletion
 * (Ctrl-A/E/U/K), Left/Right/Delete and Ctrl-L. It is only available when the
 * terminal widget stack (tui module) is present; the higher
 * {@link Order} makes it win over {@code DefaultPromptImpl}.
 * <p>
 * The terminal and reader are produced by {@link JLineProducer} and injected;
 * the terminal is released on container shutdown through {@link Destroy}.
 */
@ApplicationScoped
@Order(2000)
public class JLinePromptImpl implements Prompt {
    private final Terminal terminal;
    private final LineReader reader;
    private boolean closed;

    public JLinePromptImpl(final Terminal terminal, final LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

    @Override
    public String readLine(final String prompt) throws IOException {
        try {
            return reader.readLine(prompt);
        } catch (final UserInterruptException e) {
            throw new PromptInterruptException();
        } catch (final EndOfFileException e) {
            return null;
        }
    }

    @Override
    public boolean editing() {
        return true;
    }

    @Destroy
    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        if (terminal != null) {
            try {
                terminal.close();
            } catch (final IOException ignored) {
                // best-effort: the process is going down anyway
            }
        }
    }
}
