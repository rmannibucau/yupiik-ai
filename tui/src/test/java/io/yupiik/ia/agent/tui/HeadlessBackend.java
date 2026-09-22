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

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.terminal.Backend;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * In-memory terminal backend capturing drawn buffers so tests can assert on the
 * rendered screen. Read/peek answer -2 (no input data available), mirroring the
 * sentinel used by the JLine backend.
 */
final class HeadlessBackend implements Backend {
    static final int WIDTH = 120;
    static final int HEIGHT = 40;

    private final Object lock = new Object();
    private final Buffer buffer = Buffer.empty(new Rect(0, 0, WIDTH, HEIGHT));
    private final Semaphore drawn = new Semaphore(0);

    @Override
    public Size size() {
        return new Size(WIDTH, HEIGHT);
    }

    @Override
    public void draw(final DiffResult diff) {
        synchronized (lock) {
            for (int i = 0; i < diff.size(); i++) {
                buffer.set(diff.getX(i), diff.getY(i), diff.getCell(i));
            }
        }
        drawn.release();
    }

    /**
     * Awaits a rendered frame so tests synchronize on real UI updates instead of sleeping.
     *
     * @return {@code true} when a frame was rendered before the timeout.
     */
    boolean awaitFrame(final long timeoutMillis) throws InterruptedException {
        return drawn.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void flush() {}

    @Override
    public void clear() {}

    @Override
    public void showCursor() {}

    @Override
    public void hideCursor() {}

    @Override
    public Position getCursorPosition() {
        return Position.ORIGIN;
    }

    @Override
    public void setCursorPosition(final Position position) {}

    @Override
    public void enterAlternateScreen() {}

    @Override
    public void leaveAlternateScreen() {}

    @Override
    public void enableRawMode() {}

    @Override
    public void disableRawMode() {}

    @Override
    public void onResize(final Runnable callback) {}

    @Override
    public int read(final int timeoutMillis) {
        return -2;
    }

    @Override
    public int peek(final int timeoutMillis) {
        return -2;
    }

    @Override
    public void close() {}

    String text() {
        synchronized (lock) {
            final var sb = new StringBuilder();
            for (int y = 0; y < buffer.height(); y++) {
                final var line = new StringBuilder();
                for (int x = 0; x < buffer.width(); x++) {
                    final var cell = buffer.get(x, y);
                    if (cell.isContinuation()) {
                        continue;
                    }
                    line.append(cell.isEmpty() ? ' ' : cell.symbol());
                }
                sb.append(line.toString().stripTrailing()).append('\n');
            }
            return sb.toString();
        }
    }

    /** Symbol at a screen coordinate, or a space when the cell is empty. */
    String symbol(final int x, final int y) {
        synchronized (lock) {
            return y < 0
                            || y >= buffer.height()
                            || x < 0
                            || x >= buffer.width()
                            || buffer.get(x, y).isEmpty()
                    ? " "
                    : buffer.get(x, y).symbol();
        }
    }

    /** Foreground color at a screen coordinate. */
    Optional<Color> fg(final int x, final int y) {
        synchronized (lock) {
            return buffer.get(x, y).style().fg();
        }
    }

    /** True when the cell at the coordinate carries the reversed-video modifier. */
    boolean reversed(final int x, final int y) {
        synchronized (lock) {
            if (y < 0 || y >= buffer.height() || x < 0 || x >= buffer.width()) {
                return false;
            }
            return buffer.get(x, y).style().effectiveModifiers().contains(Modifier.REVERSED);
        }
    }

    /** Last reversed-video cell in the buffer (bottommost, rightmost), or {@code null} when none. */
    int[] lastReversed() {
        synchronized (lock) {
            for (var y = buffer.height() - 1; y >= 0; y--) {
                for (var x = buffer.width() - 1; x >= 0; x--) {
                    if (buffer.get(x, y).style().effectiveModifiers().contains(Modifier.REVERSED)) {
                        return new int[] {x, y};
                    }
                }
            }
            return null;
        }
    }
}
