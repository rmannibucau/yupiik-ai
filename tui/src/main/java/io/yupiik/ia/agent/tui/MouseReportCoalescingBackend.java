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

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

/**
 * Wraps the terminal {@link Backend} so a mouse report split by TamboUI's
 * byte-at-a-time parser timeout is never exposed to the application as junk
 * characters.
 * <p>
 * Every mouse move streams an SGR extended-mouse report
 * ({@code ESC[&lt;button;col;rowM}, {@code m} for release). TamboUI's
 * {@code EventParser} reads those bytes one at a time with a 50ms per-read
 * timeout; under render load a timeout can expire mid-report. The parser then
 * consumes a prefix (emitting a standalone {@code ESCAPE} or {@code UNKNOWN}
 * event) and the leftover suffix ({@code [&lt;35;23;35M}, {@code ;23;35M}, ...)
 * is delivered in a later batch as ordinary printable characters that would be
 * typed into a prompt.
 * <p>
 * This backend makes that impossible at the byte level: while the parser is
 * consuming a report-shaped sequence ({@code ESC[&lt;...}) it shadows the bytes
 * and, when a read times out with the terminal empty, replays the whole
 * buffered report contiguously on the next fresh read so {@code parseMouseSGR}
 * re-enters and emits a single {@code MouseEvent} with the exact coordinates.
 * A report that is genuinely truncated (no continuation ever arrives) is
 * abandoned and the buffered bytes are re-served transparently, so real
 * keystrokes such as {@code ESC} followed by {@code [mermaid] are preserved
 * verbatim - nothing is ever dropped or synthesized.
 * <p>
 * All non-input methods delegate to the wrapped backend unchanged; the
 * mouse-capture / paste / mode methods must be delegated explicitly because the
 * {@link Backend} interface defaults are no-ops (a naive proxy would silently
 * break {@code ?1003h} motion, bracketed paste, synchronized updates and
 * window-title writes).
 */
public class MouseReportCoalescingBackend implements Backend {
    private static final int NORMAL = 0, ESC_SEEN = 1, CSI_ARMED = 2, MOUSE = 3;

    private final Backend delegate;
    private final StringBuilder buffer = new StringBuilder();
    private int position;
    private boolean holding;
    private volatile boolean closed;
    private boolean pendingEsc;
    private int state = NORMAL;

    public MouseReportCoalescingBackend(final Backend delegate) {
        this.delegate = delegate;
    }

    @Override
    public int read(final int timeoutMillis) throws java.io.IOException {
        if (closed) {
            return -1;
        }
        if (pendingEsc) {
            final var p0 = delegate.peek(0);
            if (p0 == -2) {
                return -2;
            }
            if (p0 == -1) {
                return -1;
            }
            if (p0 == '[') {
                delegate.read(0);
                final var p1 = delegate.peek(0);
                if (p1 == '<') {
                    buffer.setLength(0);
                    buffer.append('\u001b').append('[');
                    position = 1;
                    holding = true;
                    pendingEsc = false;
                    state = ESC_SEEN;
                    return '\u001b';
                }
                pendingEsc = false;
                state = NORMAL;
                return '[';
            }
            pendingEsc = false;
            state = NORMAL;
            return delegate.read(0);
        }
        if (holding && position == 0) {
            final var ahead = delegate.peek(0);
            if (ahead == -2) {
                return -2;
            }
            if (ahead == -1) {
                return -1;
            }
        }
        final var fromBuffer = holding && position < buffer.length();
        // dribble-parity: if the parser is mid-report and the terminal actually has more bytes
        // (the -2 that armed the hold was a transient), serve them directly in the current
        // parse session so a continuously-streamed report completes as one MOUSE like raw.
        if ((state == MOUSE || state == CSI_ARMED) && !fromBuffer && delegate.peek(0) != -2) {
            final var b = delegate.read(0);
            if (b == -1) {
                return -1;
            }
            if (b == -2) {
                holding = true;
                position = 0;
                state = NORMAL;
                return -2;
            }
            if (state == CSI_ARMED) {
                if (b == '<') {
                    state = MOUSE;
                    buffer.append('<');
                } else {
                    state = NORMAL;
                    buffer.setLength(0);
                }
            } else if (state == MOUSE) {
                buffer.append((char) b);
                if (b == 'M' || b == 'm') {
                    state = NORMAL;
                    buffer.setLength(0);
                    position = 0;
                    holding = false;
                }
            }
            return b;
        }
        final int b;
        if (fromBuffer) {
            b = buffer.charAt(position++);
            if (position == buffer.length()) {
                holding = false;
            }
        } else if (holding) {
            holding = false;
            b = delegate.read(timeoutMillis);
        } else {
            b = delegate.read(timeoutMillis);
        }
        if (b == -1) {
            return -1;
        }
        if (b == -2) {
            if (state == MOUSE || state == CSI_ARMED) {
                holding = true;
                position = 0;
                state = NORMAL;
                return -2;
            }
            if (state == ESC_SEEN) {
                pendingEsc = true;
                state = NORMAL;
                buffer.setLength(0);
                position = 0;
                return -2;
            }
            return -2;
        }
        switch (state) {
            case NORMAL -> {
                if (b == 27) {
                    state = ESC_SEEN;
                    if (!fromBuffer) {
                        buffer.setLength(0);
                        buffer.append('\u001b');
                        position = 0;
                    }
                }
            }
            case ESC_SEEN -> {
                if (b == '[') {
                    state = CSI_ARMED;
                    if (!fromBuffer) {
                        buffer.append('[');
                    }
                } else {
                    state = NORMAL;
                    if (!fromBuffer) {
                        buffer.setLength(0);
                    }
                }
            }
            case CSI_ARMED -> {
                if (b == '<') {
                    state = MOUSE;
                    if (!fromBuffer) {
                        buffer.append('<');
                    }
                } else {
                    state = NORMAL;
                    if (!fromBuffer) {
                        buffer.setLength(0);
                    }
                }
            }
            case MOUSE -> {
                if (!fromBuffer) {
                    buffer.append((char) b);
                }
                if (b == 'M' || b == 'm') {
                    state = NORMAL;
                    buffer.setLength(0);
                    position = 0;
                    holding = false;
                }
            }
            default -> {}
        }
        return b;
    }

    @Override
    public int peek(final int timeoutMillis) throws java.io.IOException {
        if (closed) {
            return -1;
        }
        if (holding && position < buffer.length()) {
            return buffer.charAt(position);
        }
        final var p = delegate.peek(timeoutMillis);
        if (p == -2) {
            if (state == ESC_SEEN) {
                pendingEsc = true;
                state = NORMAL;
                buffer.setLength(0);
                position = 0;
            }
            return -2;
        }
        if (p != -2 && p != -1 && state == ESC_SEEN && p != '[') {
            state = NORMAL;
            buffer.setLength(0);
        }
        return p;
    }

    @Override
    public void draw(final DiffResult diff) throws java.io.IOException {
        delegate.draw(diff);
    }

    @Override
    public void flush() throws java.io.IOException {
        delegate.flush();
    }

    @Override
    public void clear() throws java.io.IOException {
        delegate.clear();
    }

    @Override
    public Size size() throws java.io.IOException {
        return delegate.size();
    }

    @Override
    public void showCursor() throws java.io.IOException {
        delegate.showCursor();
    }

    @Override
    public void hideCursor() throws java.io.IOException {
        delegate.hideCursor();
    }

    @Override
    public Position getCursorPosition() throws java.io.IOException {
        return delegate.getCursorPosition();
    }

    @Override
    public void setCursorPosition(final Position position) throws java.io.IOException {
        delegate.setCursorPosition(position);
    }

    @Override
    public void enterAlternateScreen() throws java.io.IOException {
        delegate.enterAlternateScreen();
    }

    @Override
    public void leaveAlternateScreen() throws java.io.IOException {
        delegate.leaveAlternateScreen();
    }

    @Override
    public void enableRawMode() throws java.io.IOException {
        delegate.enableRawMode();
    }

    @Override
    public void disableRawMode() throws java.io.IOException {
        delegate.disableRawMode();
    }

    @Override
    public void enableMouseCapture() throws java.io.IOException {
        delegate.enableMouseCapture();
    }

    @Override
    public void enableMouseCapture(final boolean motion) throws java.io.IOException {
        delegate.enableMouseCapture(motion);
    }

    @Override
    public void disableMouseCapture() throws java.io.IOException {
        delegate.disableMouseCapture();
    }

    @Override
    public void beginSynchronizedUpdate() throws java.io.IOException {
        delegate.beginSynchronizedUpdate();
    }

    @Override
    public void endSynchronizedUpdate() throws java.io.IOException {
        delegate.endSynchronizedUpdate();
    }

    @Override
    public void enableBracketedPaste() throws java.io.IOException {
        delegate.enableBracketedPaste();
    }

    @Override
    public void disableBracketedPaste() throws java.io.IOException {
        delegate.disableBracketedPaste();
    }

    @Override
    public void scrollUp(final int lines) throws java.io.IOException {
        delegate.scrollUp(lines);
    }

    @Override
    public void scrollDown(final int lines) throws java.io.IOException {
        delegate.scrollDown(lines);
    }

    @Override
    public void writeRaw(final byte[] bytes) throws java.io.IOException {
        delegate.writeRaw(bytes);
    }

    @Override
    public void writeRaw(final String text) throws java.io.IOException {
        delegate.writeRaw(text);
    }

    @Override
    public void writeRaw(final CharSequence text) throws java.io.IOException {
        delegate.writeRaw(text);
    }

    @Override
    public void insertLines(final int lines) throws java.io.IOException {
        delegate.insertLines(lines);
    }

    @Override
    public void deleteLines(final int lines) throws java.io.IOException {
        delegate.deleteLines(lines);
    }

    @Override
    public void moveCursorUp(final int lines) throws java.io.IOException {
        delegate.moveCursorUp(lines);
    }

    @Override
    public void moveCursorDown(final int lines) throws java.io.IOException {
        delegate.moveCursorDown(lines);
    }

    @Override
    public void moveCursorRight(final int columns) throws java.io.IOException {
        delegate.moveCursorRight(columns);
    }

    @Override
    public void moveCursorLeft(final int columns) throws java.io.IOException {
        delegate.moveCursorLeft(columns);
    }

    @Override
    public void eraseToEndOfLine() throws java.io.IOException {
        delegate.eraseToEndOfLine();
    }

    @Override
    public void setWindowTitle(final String title) throws java.io.IOException {
        delegate.setWindowTitle(title);
    }

    @Override
    public void saveWindowTitle() throws java.io.IOException {
        delegate.saveWindowTitle();
    }

    @Override
    public void restoreWindowTitle() throws java.io.IOException {
        delegate.restoreWindowTitle();
    }

    @Override
    public void carriageReturn() throws java.io.IOException {
        delegate.carriageReturn();
    }

    @Override
    public void onResize(final Runnable callback) {
        delegate.onResize(callback);
    }

    @Override
    public void close() throws java.io.IOException {
        closed = true;
        delegate.close();
    }
}
