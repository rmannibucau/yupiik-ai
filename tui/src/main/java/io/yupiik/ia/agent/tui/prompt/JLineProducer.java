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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Producer for the JLine terminal and its reader so they can be injected
 * (instead of being built lazily inside {@link JLinePromptImpl}).
 */
@DefaultScoped
public class JLineProducer {
    @Bean
    @ApplicationScoped
    public Terminal terminal(final HackStdIo stdio) {
        try {
            if (stdio.installed()) {
                // NOT system(true): that resolves System.in/System.out at build time, and
                // the runtime takes System.out over (HackStdIo) so every write is routed
                // to the command owning the terminal. jline must keep writing to the
                // pristine stream instead, otherwise its own painting would re-enter the
                // prompt sink and loop.
                // A terminal built from custom streams has no fd to ioctl: jline then
                // reports a 0x0 size and paints its ">...." off-screen marker as the
                // prompt, so the size is resolved against the real tty here. It is only
                // refreshed at build time (a mid-session resize keeps this width until
                // the next start).
                return TerminalBuilder.builder()
                        .streams(System.in, stdio.out())
                        .size(terminalSize())
                        .build();
            }
            return TerminalBuilder.builder().system(true).build();
        } catch (final IOException e) {
            throw new UncheckedIOException("Can't initialize the JLine terminal", e);
        }
    }

    /**
     * Resolves the size of the terminal owning the real tty without touching a file
     * descriptor (the streams jline got are pristine copies, not the tty): a throwaway
     * {@code system(true)} terminal measures it through the normal jline path
     * ({@code ioctl}), then is closed - never read, so the terminal mode is left
     * untouched. Falls back to the classic 80x24 when no tty is available (piped
     * output, headless) - any sane non-zero size keeps jline from painting its
     * off-screen ">...." marker.
     */
    private static Size terminalSize() {
        try (final Terminal probe = TerminalBuilder.builder().system(true).build()) {
            final var size = probe.getSize();
            if (size.getRows() > 0 && size.getColumns() > 0) {
                return size;
            }
        } catch (final Exception ignored) {
            // no tty: fallback below
        }
        return new Size(80, 24);
    }

    @Bean
    @ApplicationScoped
    public LineReader lineReader(final Terminal terminal, final SlashCommandCompleter completer) {
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                // AUTO_FRESH_LINE is deliberately NOT set: its freshLine() widget runs
                // `clr_eol` + `columns-2` spaces at every prompt, which erases whatever
                // the row already holds and scrolls the screen when the cursor was not
                // at column 0 - erasing the row is what made the prompt disappear. The
                // deterministic equivalent (one line break, and only when the previous
                // raw write was left unterminated) is done by JLinePromptImpl.
                .build();
    }
}
