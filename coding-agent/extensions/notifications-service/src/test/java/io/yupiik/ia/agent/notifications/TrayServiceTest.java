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

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.ia.agent.notifications.tray.NativeTray;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TrayServiceTest {
    @Test
    void idleLoopBlocksWithoutTimerAndPublicationWakesIt() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class);
                final var executor = Executors.newSingleThreadExecutor()) {
            final var state = new UnreadNotifications();
            final var tray = new EventTray();
            final var loop = executor.submit(() -> {
                new TrayService(endpoint.instance(), Clock.systemUTC()).run(state, tray, false);
                return null;
            });
            try {
                assertTrue(tray.waiting.await(2, TimeUnit.SECONDS));
                assertEquals(0, tray.timeout);
                state.publish(new Notice(1, "s", "Reactive notification", "approval_required", "main", 1));
                loop.get(3, TimeUnit.SECONDS);
                assertEquals(1, tray.waits);
                assertEquals(2, tray.updates);
                assertTrue(tray.title.contains("Approval requested - Reactive notification"));
                assertTrue(tray.disposed);
            } finally {
                tray.stopped = true;
                tray.wakeup();
            }
        }
    }

    @Test
    void acknowledgmentClearsRemindersAndDisposesTray() throws Exception {
        final var clock = Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC);
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class)) {
            final var state = new UnreadNotifications();
            state.publish(new Notice(1, "s", "Session", "approval_required", "main", 2000));
            final var tray = new FakeTray(true);
            new TrayService(endpoint.instance(), clock).run(state, tray, false);
            assertTrue(tray.disposed);
            assertTrue(tray.sawAttention);
            assertEquals("YCA - no unread activity", tray.title);
            assertTrue(state.read().isEmpty());
        }
    }

    @Test
    void stoppingWithoutAcknowledgmentDoesNotAcknowledgeYcaRequests() throws Exception {
        final var clock = Clock.fixed(Instant.ofEpochMilli(3000), ZoneOffset.UTC);
        try (final var container = ConfiguringContainer.of().start();
                final var endpoint = container.lookup(NotificationEndpoint.class)) {
            final var state = new UnreadNotifications();
            state.publish(new Notice(1, "s", "Session", "input_required", "main", 3000));
            final var tray = new FakeTray(false);
            new TrayService(endpoint.instance(), clock).run(state, tray, true);
            assertTrue(tray.disposed);
            assertFalse(tray.sawAttention); // odd second of the optional blink cycle
            assertEquals(1, state.read().size());
        }
    }

    private static final class FakeTray implements NativeTray {
        private final boolean acknowledge;
        private int updates;
        private int polls;
        private boolean disposed;
        private boolean sawAttention;
        private String title;

        private FakeTray(final boolean acknowledge) {
            this.acknowledge = acknowledge;
        }

        @Override
        public void update(final String tooltip, final boolean attention) {
            title = tooltip;
            sawAttention |= attention;
            updates++;
        }

        @Override
        public void poll() {
            polls++;
        }

        @Override
        public void await(final long timeoutMillis) {
            // The scripted menu event is ready for the next dispatch.
        }

        @Override
        public void wakeup() {
            // The scripted tray never blocks.
        }

        @Override
        public boolean acknowledged() {
            return acknowledge && updates == 1 && polls == 2;
        }

        @Override
        public boolean closed() {
            return acknowledge ? updates >= 2 : updates >= 1;
        }

        @Override
        public void close() {
            disposed = true;
        }
    }

    private static final class EventTray implements NativeTray {
        private final CountDownLatch waiting = new CountDownLatch(1);
        private final Semaphore events = new Semaphore(0);
        private volatile boolean stopped;
        private long timeout;
        private int waits;
        private int updates;
        private String title;
        private boolean disposed;

        @Override
        public void update(final String tooltip, final boolean attention) {
            title = tooltip;
            if (++updates == 2) {
                stopped = true;
            }
        }

        @Override
        public void poll() {}

        @Override
        public void await(final long timeoutMillis) {
            timeout = timeoutMillis;
            waits++;
            waiting.countDown();
            try {
                if (timeoutMillis == 0) {
                    events.acquire();
                } else {
                    events.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void wakeup() {
            events.release();
        }

        @Override
        public boolean acknowledged() {
            return false;
        }

        @Override
        public boolean closed() {
            return stopped;
        }

        @Override
        public void close() {
            disposed = true;
        }
    }
}
