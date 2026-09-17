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
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
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
    public Terminal terminal() {
        try {
            return TerminalBuilder.builder().system(true).build();
        } catch (final IOException e) {
            throw new UncheckedIOException("Can't initialize the JLine terminal", e);
        }
    }

    @Bean
    @ApplicationScoped
    public LineReader lineReader(final Terminal terminal, final SlashCommandCompleter completer) {
        return LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
    }
}
