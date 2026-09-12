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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class SpinnerTest {
    @Test
    void inertWithoutTty() {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(false), new PrintStream(out, true), false);
        spinner.start("thinking");
        spinner.stop();
        assertEquals(0, out.size());
    }

    @Test
    void doubleStartDoesNotOrphanTheFirstAnimationThread() throws InterruptedException {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(true), new PrintStream(out), true);
        spinner.start("thinking");
        spinner.start("thinking");
        Thread.sleep(250);
        spinner.stop();
        final var sizeAfterStop = out.size();
        assertTrue(sizeAfterStop > 0);
        Thread.sleep(250);
        assertEquals(sizeAfterStop, out.size(), "a leaked animation thread kept writing after stop()");
    }

    @Test
    void restartAfterStopAnimatesAgain() throws InterruptedException {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(true), new PrintStream(out), true);
        spinner.start("thinking");
        Thread.sleep(250);
        spinner.stop();
        final var sizeAfterFirstStop = out.size();
        spinner.start("thinking");
        Thread.sleep(250);
        spinner.stop();
        assertTrue(out.size() > sizeAfterFirstStop, "no frames after restart");
    }
}
