package io.yupiik.ia.agent.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

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
    }

    @Override
    public void flush() {
    }

    @Override
    public void clear() {
    }

    @Override
    public void showCursor() {
    }

    @Override
    public void hideCursor() {
    }

    @Override
    public Position getCursorPosition() {
        return Position.ORIGIN;
    }

    @Override
    public void setCursorPosition(final Position position) {
    }

    @Override
    public void enterAlternateScreen() {
    }

    @Override
    public void leaveAlternateScreen() {
    }

    @Override
    public void enableRawMode() {
    }

    @Override
    public void disableRawMode() {
    }

    @Override
    public void onResize(final Runnable callback) {
    }

    @Override
    public int read(final int timeoutMillis) {
        return -2;
    }

    @Override
    public int peek(final int timeoutMillis) {
        return -2;
    }

    @Override
    public void close() {
    }

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
}
