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

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

/**
 * A lightweight terminal activity indicator for waiting periods (LLM first
 * token, long tool execution). Writes a single spinner line to stderr using
 * carriage returns and erases itself on {@link #stop()}. Inert when the
 * output is not an interactive terminal or no frames are configured, so it
 * never corrupts piped/scripted output.
 */
public final class Spinner {
    private final Symbols symbols;
    private final PrintStream out;
    private final Lock lock;
    private final boolean active;
    private final long frameIntervalMillis;
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    private volatile String label;

    public Spinner(final Symbols symbols, final PrintStream out, final boolean tty, final Lock lock) {
        this(symbols, out, tty, lock, 80L);
    }

    Spinner(
            final Symbols symbols,
            final PrintStream out,
            final boolean tty,
            final Lock lock,
            final long frameIntervalMillis) {
        this.symbols = symbols;
        this.out = out;
        this.lock = lock;
        this.active = tty && symbols.spinner().length > 0;
        this.frameIntervalMillis = frameIntervalMillis;
    }

    public void start(final String label) {
        if (!active || !running.compareAndSet(false, true)) {
            return;
        }
        this.label = label;
        thread = Thread.ofVirtual().name("cli-spinner").start(() -> {
            final var frames = symbols.spinner();
            int i = 0;
            while (running.get()) {
                final var seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                // tryLock: while a real output write holds the render lock the frame is
                // dropped instead of queueing behind the write and landing on a
                // half-drawn line - the spinner is simply ignored while output flows
                if (lock.tryLock()) {
                    try {
                        out.print('\r'
                                + String.valueOf(frames[i++ % frames.length])
                                + ' '
                                + label
                                + (seconds >= 1 ? String.format(" %.1fs", seconds) : ""));
                        out.flush();
                    } finally {
                        lock.unlock();
                    }
                }
                try {
                    Thread.sleep(frameIntervalMillis);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    Thread worker() {
        return thread;
    }

    /**
     * Erases the spinner line (keeps the current status text as a static line
     * on stderr when {@code keepLabel} is true).
     */
    public void stop() {
        stop(false);
    }

    public void stop(final boolean keepLabel) {
        if (!active) {
            return;
        }
        running.set(false);
        final var worker = thread;
        thread = null;
        if (worker != null) {
            // Guarantee the worker fully exited before we erase the line: a
            // short join could return while a stale frame is still being
            // written, letting it land after the erase and interleave with the
            // interaction prompt that draws right after stop().
            worker.interrupt();
            try {
                worker.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lock.lock();
        try {
            out.print('\r');
            if (keepLabel && label != null) {
                out.print(symbols.infoMarker() + label);
            }
            out.print("\r");
            out.print("\u001b[2K");
            out.print('\r');
            out.flush();
        } finally {
            lock.unlock();
        }
    }
}
