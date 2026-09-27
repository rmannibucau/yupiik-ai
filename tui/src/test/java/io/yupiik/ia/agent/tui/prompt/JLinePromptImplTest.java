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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;

class JLinePromptImplTest {

    @Test
    void mapsJLineExceptionsToThePromptContract() throws Exception {
        final var calls = new AtomicInteger();
        final var prompts = new AtomicReference<String>();
        final var reader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("readLine")) {
                        return null;
                    }
                    prompts.set((String) args[0]);
                    return switch (calls.getAndIncrement()) {
                        case 0 -> "hello";
                        case 1 -> throw new UserInterruptException("ctrl-c");
                        default -> throw new EndOfFileException();
                    };
                });
        final var prompt = new JLinePromptImpl(reader);

        assertTrue(prompt.editing());
        assertEquals(Optional.of("hello"), prompt.readLine("> "));
        assertThrows(PromptInterruptException.class, () -> prompt.readLine("> "));
        assertEquals(Optional.empty(), prompt.readLine("> "));
    }

    @Test
    void escapesPerCentForTheJLinePromptPattern() throws Exception {
        final var seen = new AtomicReference<String>();
        final var prompt = new JLinePromptImpl(reader(arg -> {
            seen.set(arg);
            return null;
        }));
        assertEquals(Optional.empty(), prompt.readLine(">>>%>MID< <<"));
        assertEquals(">>>%%>MID< <<", seen.get(), "the reader must receive escaped percents");
    }

    @Test
    void escapesLiteralPerCentsOnly() {
        assertEquals("a%%>b", JLinePromptImpl.escapePromptForJLine("a%>b"));
        assertEquals(">>>%%>MID< <<", JLinePromptImpl.escapePromptForJLine(">>>%>MID< <<"));
        assertEquals("0%%> ", JLinePromptImpl.escapePromptForJLine("0%> "));
        assertEquals("x%%y", JLinePromptImpl.escapePromptForJLine("x%%y"));
        assertEquals("prompt", JLinePromptImpl.escapePromptForJLine("prompt"));
        assertEquals("%{}", JLinePromptImpl.escapePromptForJLine("%{}"));
        assertEquals("%N %M %{hidden%}", JLinePromptImpl.escapePromptForJLine("%N %M %{hidden%}"));
        assertEquals("%P10.", JLinePromptImpl.escapePromptForJLine("%P10."));
        assertEquals("%-5P", JLinePromptImpl.escapePromptForJLine("%-5P"));
        assertEquals("abc%%", JLinePromptImpl.escapePromptForJLine("abc%"));
    }

    @Test
    void standaloneEscIsAnInterruptAndEscapeSequencesAreConsumed() {
        // ESC alone at the end of the stream: a standalone Esc press -> interrupt
        final var promptEsc = new JLinePromptImpl(readerWithTerminal(new ScriptedReader('\033')));
        assertTrue(promptEsc.isInterruptAvailable());
        assertTrue(promptEsc.pollInterrupt(), "a standalone Esc must be reported as an interrupt");
        assertFalse(promptEsc.pollInterrupt(), "an empty stream must not interrupt");

        // ESC [ A (arrow key): the whole sequence is consumed without interrupting
        final var promptArrow = new JLinePromptImpl(readerWithTerminal(new ScriptedReader('\033', '[', 'A')));
        assertFalse(promptArrow.pollInterrupt(), "an escape sequence must not interrupt");
        assertFalse(promptArrow.pollInterrupt(), "the sequence must be fully consumed");
    }

    /**
     * Deterministic in-memory {@link org.jline.utils.NonBlockingReader}: characters
     * are served synchronously (no feeder thread), exhausted reads return
     * {@code READ_EXPIRED} and a peek never consumes.
     */
    private static final class ScriptedReader extends org.jline.utils.NonBlockingReader {
        private final int[] chars;
        private int index;

        private ScriptedReader(final int... chars) {
            this.chars = chars;
        }

        @Override
        protected int read(final long timeout, final boolean isPeek) {
            if (index >= chars.length) {
                return READ_EXPIRED;
            }
            final var value = chars[index];
            if (!isPeek) {
                index++;
            }
            return value;
        }

        @Override
        public int readBuffered(final char[] buffer, final int offset, final int length, final long timeout) {
            return -1;
        }

        @Override
        public void close() {
            // in-memory synchronous reader: nothing to release
        }
    }

    /** LineReader proxy whose Terminal only serves {@code reader()}; nothing else is touched by the prompt. */
    private LineReader readerWithTerminal(final org.jline.utils.NonBlockingReader nonBlocking) {
        final var terminal = (org.jline.terminal.Terminal) Proxy.newProxyInstance(
                org.jline.terminal.Terminal.class.getClassLoader(),
                new Class<?>[] {org.jline.terminal.Terminal.class},
                (proxy, method, args) -> method.getName().equals("reader") ? nonBlocking : null);
        return (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(),
                new Class<?>[] {LineReader.class},
                (proxy, method, args) -> method.getName().equals("getTerminal") ? terminal : null);
    }

    private LineReader reader(final java.util.function.Function<String, Object> onReadLine) {
        return (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) {
                        return onReadLine.apply((String) args[0]);
                    }
                    return null;
                });
    }
}
