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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class SpinnerTest {
    @Test
    void inertWithoutTty() {
        final var out = new ByteArrayOutputStream();
        final var spinner = new Spinner(new Symbols(false), new PrintStream(out, true), false, new ReentrantLock());
        spinner.start("thinking");
        spinner.stop();
        assertEquals(0, out.size());
    }

    @Test
    void doubleStartDoesNotOrphanTheFirstAnimationThread() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(
                new Symbols(true), stream.printStream(), true, new ReentrantLock(), TimeUnit.MINUTES.toMillis(1));
        spinner.start("thinking");
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        spinner.stop();
        final var sizeAfterStop = stream.size();
        assertTrue(sizeAfterStop > 0);
        assertEquals(sizeAfterStop, stream.size(), "a leaked animation thread kept writing after stop()");
    }

    @Test
    void restartAfterStopAnimatesAgain() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(
                new Symbols(true), stream.printStream(), true, new ReentrantLock(), TimeUnit.MINUTES.toMillis(1));
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        spinner.stop();
        final var sizeAfterFirstStop = stream.size();
        stream.nextFrame();
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "no frames after restart");
        spinner.stop();
        assertTrue(stream.size() > sizeAfterFirstStop, "no frames after restart");
    }

    @Test
    void stopReturnsOnlyAfterWorkerExited() throws InterruptedException {
        final var stream = new RecordingStream();
        final var spinner = new Spinner(
                new Symbols(true), stream.printStream(), true, new ReentrantLock(), TimeUnit.MINUTES.toMillis(1));
        spinner.start("thinking");
        assertTrue(stream.awaitFrame(), "spinner did not render a frame");
        final var worker = spinner.worker();
        spinner.stop(); // must join the worker so the erase is the last write
        assertFalse(worker.isAlive(), "stop() returned before the worker exited");
        stream.nextFrame();
        // belt-and-braces: a stale frame written after stop() would trip this latch promptly;
        // a clean stop stays silent, so the short bound keeps the suite fast
        assertFalse(stream.awaitFrame(250, TimeUnit.MILLISECONDS), "a stale frame was written after stop() returned");
    }

    /**
     * Records rendered bytes and signals each write through a latch so tests await real frames instead of sleeping.
     */
    private static final class RecordingStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final AtomicReference<CountDownLatch> frame = new AtomicReference<>(new CountDownLatch(1));

        PrintStream printStream() {
            return new PrintStream(this, true);
        }

        void nextFrame() {
            frame.set(new CountDownLatch(1));
        }

        boolean awaitFrame() throws InterruptedException {
            return awaitFrame(5, TimeUnit.SECONDS);
        }

        boolean awaitFrame(final long timeout, final TimeUnit unit) throws InterruptedException {
            return frame.get().await(timeout, unit);
        }

        int size() {
            return bytes.size();
        }

        @Override
        public void write(final int b) {
            bytes.write(b);
            frame.get().countDown();
        }

        @Override
        public void write(final byte[] b, final int off, final int len) {
            bytes.write(b, off, len);
            frame.get().countDown();
        }
    }
}
