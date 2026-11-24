/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UnreadNotificationsTest {
    @Test
    void coalescesBySessionAndSourceAndPreservesNewerEvents() {
        final var state = new UnreadNotifications();
        state.publish(notice("one", "main", 1));
        state.publish(notice("one", "btw", 2));
        state.publish(notice("one", "main", 3));
        state.publish(notice("one", "main", 1));
        assertEquals(List.of(notice("one", "main", 3), notice("one", "btw", 2)), state.read());
    }

    @Test
    void acknowledgmentDoesNotEraseNewActivity() {
        final var state = new UnreadNotifications();
        state.publish(notice("one", "main", 0));
        final var displayed = state.read();
        state.publish(notice("one", "main", 1));
        state.acknowledge(displayed);
        assertEquals(List.of(notice("one", "main", 1)), state.read());
        state.acknowledge(state.read());
        assertTrue(state.read().isEmpty());
        assertTrue(new UnreadNotifications().read().isEmpty());
    }

    @Test
    void dismissesOnlyTheExactSelectedNotice() {
        final var state = new UnreadNotifications();
        final var selected = notice("one", "main", 1);
        final var sameSession = notice("one", "btw", 2);
        final var otherSession = notice("two", "main", 3);
        state.publish(selected);
        state.publish(sameSession);
        state.publish(otherSession);
        state.acknowledge(List.of(selected));
        assertEquals(List.of(otherSession, sameSession), state.read());

        final var replacement = new Notice(1, "one", "Updated title", "input_required", "btw", 2);
        state.publish(replacement);
        state.acknowledge(List.of(sameSession));
        assertEquals(List.of(otherSession, replacement), state.read());

        final var newer = notice("one", "btw", 4);
        state.publish(newer);
        state.acknowledge(List.of(replacement));
        assertEquals(List.of(newer, otherSession), state.read());
    }

    @Test
    void boundsRetainedReminders() {
        final var state = new UnreadNotifications();
        for (int i = 0; i < 258; i++) {
            state.publish(notice("session-" + i, "main", i));
        }
        assertEquals(256, state.read().size());
        assertEquals("session-257", state.read().getFirst().session());
        assertFalse(state.read().stream().anyMatch(n -> n.session().equals("session-0")));
    }

    @Test
    void acceptsConcurrentPublishers() throws Exception {
        final var state = new UnreadNotifications();
        try (final var pool = Executors.newFixedThreadPool(4)) {
            final var tasks = IntStream.range(0, 12)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        state.publish(notice("session-" + i, "main", i));
                        return null;
                    })
                    .toList();
            for (final var task : pool.invokeAll(tasks)) {
                task.get();
            }
        }
        assertEquals(12, state.read().size());
    }

    private Notice notice(final String session, final String source, final int offset) {
        return new Notice(1, session, "Session " + session, "approval_required", source, offset);
    }
}
