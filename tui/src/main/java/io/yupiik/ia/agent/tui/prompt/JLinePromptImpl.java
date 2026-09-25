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
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import java.io.IOException;
import java.util.Optional;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * JLine backed {@link Prompt} giving the plain {@code cli} REPL full line
 * editing: in-memory history (Up/Down), word motion (Alt-b/Alt-f),
 * word deletion (Alt-Backspace, Ctrl-W), line motion/deletion
 * (Ctrl-A/E/U/K), Left/Right/Delete and Ctrl-L. It is only available when the
 * terminal widget stack (tui module) is present; the higher
 * {@link Order} makes it win over {@code DefaultPromptImpl}.
 * <p>
 * The terminal and reader are produced by {@link JLineProducer}; the terminal
 * is an application-scoped bean whose lifecycle (including the close) is owned
 * by the producer, so this class only consumes the {@link LineReader}.
 */
@ApplicationScoped
@Order(2000)
public class JLinePromptImpl implements Prompt {
    private final LineReader reader;

    public JLinePromptImpl(final LineReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<String> readLine(final String prompt) throws IOException {
        try {
            return Optional.ofNullable(reader.readLine(escapePromptForJLine(prompt)));
        } catch (final UserInterruptException e) {
            throw new PromptInterruptException();
        } catch (final EndOfFileException e) {
            return Optional.empty();
        }
    }

    /**
     * JLine interprets the prompt as a prompt-pattern (bash/zsh like escapes):
     * {@code %{...%}} hidden region, {@code %N} line offset, {@code %M} message,
     * {@code %[count]P[char]} padding and {@code %%} for a literal percent.
     * Any other {@code %} is consumed together with its following character and
     * never displayed - this is how the default prompt template suffix
     * {@code %>} would silently vanish. This method doubles every {@code %}
     * that is not part of a recognised pattern so literal percents survive
     * while intentional patterns are left untouched.
     */
    static String escapePromptForJLine(final String prompt) {
        final int length = prompt.length();
        final StringBuilder builder = new StringBuilder(length + 8);
        int i = 0;
        while (i < length) {
            final char c = prompt.charAt(i);
            if (c != '%') {
                builder.append(c);
                i++;
                continue;
            }
            if (i + 1 < length) {
                final char next = prompt.charAt(i + 1);
                if (next == '%') {
                    builder.append("%%"); // already an escaped literal percent
                    i += 2;
                    continue;
                }
                if (isPatternStart(next)) {
                    builder.append(c); // intentional JLine pattern, keep as-is
                    i++;
                    continue;
                }
            }
            builder.append("%%"); // literal percent for the user
            i++;
        }
        return builder.toString();
    }

    private static boolean isPatternStart(final char c) {
        return c == '{' || c == '}' || c == 'N' || c == 'M' || c == 'P' || c == '-' || (c >= '0' && c <= '9');
    }

    @Override
    public boolean editing() {
        return true;
    }
}
