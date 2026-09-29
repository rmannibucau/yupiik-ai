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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Guards the terminal wiring behind the prompt: jline built from custom streams
 * has no fd to measure, reports a 0x0 size and paints its ">...." off-screen
 * marker instead of the prompt - so a usable size must be provided.
 */
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class JLineProducerTest {
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void hijackedTerminalStillReportsAUsableSize() throws Exception {
        final var stdio = new HackStdIo();
        final var originalIn = System.in;
        try {
            stdio.install();
            // jline derives the terminal from System.in: when the fork is attached to a
            // real terminal it allocates a PosixPtyTerminal whose pump threads outlive
            // System.exit() and push surefire into its 30s kill-self-fork path. Hiding
            // the tty behind a plain pipe keeps the streams path deterministic anywhere.
            System.setIn(new ByteArrayInputStream(new byte[0]));
            assertTrue(stdio.installed(), "the runtime must have taken the streams over");
            try (final var terminal = new JLineProducer().terminal(stdio)) {
                assertUsableSize(terminal);
            }
        } finally {
            System.setIn(originalIn);
            stdio.uninstall();
        }
    }

    @Test
    void untouchedRuntimeKeepsTheSystemTerminal() throws Exception {
        try (final var terminal = new JLineProducer().terminal(new HackStdIo())) {
            assertNotNull(terminal, "a terminal must always be produced");
            assertNotNull(terminal.getSize(), "a size must always be exposed");
        }
    }

    private static void assertUsableSize(final org.jline.terminal.Terminal terminal) {
        final var size = terminal.getSize();
        assertTrue(size.getRows() > 0, "rows must never be zero: jline then paints its '>....' marker");
        assertTrue(size.getColumns() > 0, "columns must never be zero: jline cannot lay the prompt out");
    }
}
