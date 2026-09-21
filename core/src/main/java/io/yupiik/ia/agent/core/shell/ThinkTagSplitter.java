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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Streaming splitter for models that inline their reasoning in the text channel
 * between {@code <think>...</think>} tags (GLM-style gateways), optionally mixed
 * with markdown fences.
 *
 * <p>Regex per accumulated chunk cannot work here: delimiters can be split across
 * chunks and reasoning must not leak. This service therefore hands out {@link Parser}
 * state machines that keep their state across the whole stream and only hold a
 * bounded tail buffer - no re-scan of the accumulated text ever happens.
 *
 * <p>The service itself is stateless (application scoped bean); all per-stream
 * state lives in a {@link Parser} instance.
 */
@ApplicationScoped
public class ThinkTagSplitter {
    /** Channel a segment of the stream belongs to. */
    public enum Channel {
        VISIBLE,
        THINKING,
        FENCED
    }

    /** A classified slice of the stream. */
    public record Segment(Channel channel, String text) {}

    /** One-shot classification result: visible content (fences verbatim) vs reasoning. */
    public record Parts(String visible, String thinking) {}

    private static final Pattern OPEN_THINK = Pattern.compile("<think[ \\t]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE_THINK = Pattern.compile("</think[ \\t]*>", Pattern.CASE_INSENSITIVE);
    private static final String BACKTICK_FENCE = "```";
    private static final String TILDE_FENCE = "~~~";
    /** Bounded tail window: a delimiter can be at most 8 chars, keep a 7-char suffix. */
    private static final int WINDOW = 16;

    /** Creates a fresh parser for one stream. */
    public Parser create() {
        return new Parser();
    }

    /** One-shot classification of a complete text (convenience over {@link Parser}). */
    public Parts split(final String text) {
        final var parser = create();
        final var visible = new StringBuilder();
        final var thinking = new StringBuilder();
        for (final var segment : parser.feed(text == null ? "" : text)) {
            accumulate(segment, visible, thinking);
        }
        for (final var segment : parser.flush()) {
            accumulate(segment, visible, thinking);
        }
        return new Parts(visible.toString(), thinking.toString());
    }

    private static void accumulate(final Segment segment, final StringBuilder visible, final StringBuilder thinking) {
        if (segment.channel() == Channel.THINKING) {
            thinking.append(segment.text());
        } else {
            visible.append(segment.text());
        }
    }

    /** Streaming state machine: feed deltas, collect segments, flush at end of stream. */
    public static final class Parser {
        private enum State {
            NORMAL,
            THINKING,
            FENCED
        }

        private record FenceMatch(int index, String token) {}

        private final StringBuilder carry = new StringBuilder();
        private final List<Segment> out = new ArrayList<>();
        private State state = State.NORMAL;
        private String fenceToken;

        /** Feeds the next stream delta and returns the segments fully classified so far. */
        public List<Segment> feed(final String delta) {
            carry.append(delta == null ? "" : delta);
            scan(false);
            return drain();
        }

        /** Ends the stream: emits every held character in the current channel. */
        public List<Segment> flush() {
            scan(true);
            if (carry.length() > 0) {
                out.add(new Segment(
                        switch (state) {
                            case THINKING -> Channel.THINKING;
                            case FENCED -> Channel.FENCED;
                            case NORMAL -> Channel.VISIBLE;
                        },
                        carry.toString()));
                carry.setLength(0);
            }
            return drain();
        }

        /** Drops all state (new stream). */
        public void reset() {
            carry.setLength(0);
            out.clear();
            state = State.NORMAL;
            fenceToken = null;
        }

        private void scan(final boolean flush) {
            while (switch (state) {
                case NORMAL -> scanNormal(flush);
                case THINKING -> scanThinking(flush);
                case FENCED -> scanFenced(flush);
            }) {
                // loop until no more progress
            }
        }

        private boolean scanNormal(final boolean flush) {
            final var open = OPEN_THINK.matcher(carry);
            final var openAt = open.find() ? open.start() : -1;
            final var fence = earliestFence();
            if (openAt < 0 && fence == null) {
                if (flush) {
                    emit(Channel.VISIBLE, 0, carry.length());
                } else {
                    hold(Channel.VISIBLE);
                }
                return false;
            }
            final int at;
            if (openAt >= 0 && (fence == null || openAt <= fence.index())) {
                at = openAt;
                carry.delete(at, at + open.group().length());
                state = State.THINKING;
            } else {
                at = fence.index();
                fenceToken = fence.token();
                carry.delete(at, at + fenceToken.length());
                state = State.FENCED;
            }
            emit(Channel.VISIBLE, 0, at);
            return true;
        }

        private boolean scanThinking(final boolean flush) {
            final var close = CLOSE_THINK.matcher(carry);
            if (!close.find()) {
                if (flush) {
                    emit(Channel.THINKING, 0, carry.length());
                } else {
                    hold(Channel.THINKING);
                }
                return false;
            }
            final var at = close.start();
            // read the match length BEFORE mutating the carry: Matcher.group() re-slices
            // the original offsets against the live buffer and would throw once shrunk
            final var tokenLength = close.group().length();
            // emit the prefix first: the deletion shifts the closing tag to index 0
            emit(Channel.THINKING, 0, at);
            carry.delete(0, tokenLength);
            state = State.NORMAL;
            return true;
        }

        private boolean scanFenced(final boolean flush) {
            final var at = carry.indexOf(fenceToken);
            if (at < 0) {
                if (flush) {
                    emit(Channel.FENCED, 0, carry.length());
                } else {
                    hold(Channel.FENCED);
                }
                return false;
            }
            // emit the prefix first: the deletion shifts the closing token to index 0
            emit(Channel.FENCED, 0, at);
            carry.delete(0, fenceToken.length());
            fenceToken = null;
            state = State.NORMAL;
            return true;
        }

        /** Earliest fence opener in the carry buffer, backticks winning ties. */
        private FenceMatch earliestFence() {
            final var backtick = carry.indexOf(BACKTICK_FENCE);
            final var tilde = carry.indexOf(TILDE_FENCE);
            if (backtick < 0 && tilde < 0) {
                return null;
            }
            if (backtick < 0) {
                return new FenceMatch(tilde, TILDE_FENCE);
            }
            if (tilde < 0 || backtick <= tilde) {
                return new FenceMatch(backtick, BACKTICK_FENCE);
            }
            return new FenceMatch(tilde, TILDE_FENCE);
        }

        /**
         * Emits everything that can no longer take part in a delimiter: all but the
         * first potential delimiter start found within the bounded suffix window.
         */
        private void hold(final Channel channel) {
            final var from =
                    switch (channel) {
                        case VISIBLE -> holdFrom('<', '`', '~');
                        case THINKING -> holdFrom('<');
                        case FENCED -> holdFrom(fenceToken == null ? '`' : fenceToken.charAt(0));
                    };
            if (from > 0) {
                emit(channel, 0, from);
            }
        }

        /** Index where the safe-to-emit prefix ends: the earliest viable delimiter start in the window. */
        private int holdFrom(final char... starts) {
            final var window = Math.max(0, carry.length() - WINDOW);
            for (var i = window; i < carry.length(); i++) {
                final var c = carry.charAt(i);
                for (final var start : starts) {
                    if (c == start) {
                        return i;
                    }
                }
            }
            return carry.length();
        }

        private void emit(final Channel channel, final int from, final int to) {
            if (to > from) {
                out.add(new Segment(channel, carry.substring(from, to)));
                carry.delete(from, to);
            }
        }

        private List<Segment> drain() {
            if (out.isEmpty()) {
                return List.of();
            }
            final var segments = new ArrayList<>(out);
            out.clear();
            return segments;
        }
    }
}
