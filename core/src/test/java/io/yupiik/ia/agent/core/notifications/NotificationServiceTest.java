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
package io.yupiik.ia.agent.core.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;

import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class NotificationServiceTest {

    @Test
    void eventSwitchesAreIndependentAndMasterSwitchWins() {
        for (int mask = 0; mask < 32; mask++) {
            final var deliveries = new ArrayList<Delivery>();
            final var enabled = (mask & 16) != 0;
            final var config = TestConfigs.codingAgent(Map.of(
                    "yca.notifications.enabled", Boolean.toString(enabled),
                    "yca.notifications.on.event.turnEnd", Boolean.toString((mask & 8) != 0),
                    "yca.notifications.on.event.approval", Boolean.toString((mask & 4) != 0),
                    "yca.notifications.on.tools.ask_user", Boolean.toString((mask & 2) != 0),
                    "yca.notifications.on.tools.btw", Boolean.toString((mask & 1) != 0),
                    "yca.notifications.command", "custom"));
            final var service = new NotificationService(config, executor(), (argv, env) -> {
                deliveries.add(new Delivery(argv, env));
                return new FakeProcess();
            });
            service.onTurnEnd("s", "title", "model", "provider", true, "reply");
            service.onInputRequired("s", "title", 2, "main");
            service.onInputRequired("s", "title", 1, "btw");
            service.onApprovalRequired("s", "title", "bash", "main");
            service.onApprovalRequired("s", "title", "bash", "btw");
            assertEquals(
                    enabled ? Integer.bitCount(mask & 15) + ((mask & 4) != 0 ? 1 : 0) : 0,
                    deliveries.size(),
                    "mask=" + mask);
            final var events = deliveries.stream()
                    .map(d -> d.env().get(NotificationService.ENV_TYPE) + ":"
                            + d.env().getOrDefault(NotificationService.ENV_SOURCE, ""))
                    .toList();
            assertEquals(enabled && (mask & 8) != 0, events.contains("turn_end:"), "turn end mask=" + mask);
            assertEquals(enabled && (mask & 2) != 0, events.contains("input_required:main"), "main input mask=" + mask);
            assertEquals(enabled && (mask & 1) != 0, events.contains("input_required:btw"), "btw input mask=" + mask);
            assertEquals(
                    enabled && (mask & 4) != 0,
                    events.contains("approval_required:main"),
                    "main approval mask=" + mask);
            assertEquals(
                    enabled && (mask & 4) != 0, events.contains("approval_required:btw"), "btw approval mask=" + mask);
        }
    }

    @Test
    void interactionsHaveCompactPrivatePayloadsAndNoOutcome() {
        final var deliveries = new ArrayList<Delivery>();
        final var service = new NotificationService(
                TestConfigs.codingAgent(
                        Map.of("yca.notifications.enabled", "true", "yca.notifications.command", "custom,--flag")),
                executor(),
                (argv, env) -> {
                    deliveries.add(new Delivery(argv, env));
                    return new FakeProcess();
                });
        service.onInputRequired("s1", "  My\n title\t ", 2, "main");
        service.onApprovalRequired("s2", "Review", "bash", "btw");
        assertEquals(List.of("custom", "--flag"), deliveries.getFirst().argv());
        final var input = deliveries.getFirst().env();
        assertEquals("s1 - My title", input.get(NotificationService.ENV_TITLE));
        assertEquals(
                "Input required: 2 questions waiting.\nReturn to YCA to answer.",
                input.get(NotificationService.ENV_BODY));
        assertEquals("input_required", input.get(NotificationService.ENV_TYPE));
        assertEquals("main", input.get(NotificationService.ENV_SOURCE));
        assertEquals("", input.get(NotificationService.ENV_TOOL));
        for (final var delivery : deliveries) {
            assertEquals("", delivery.env().get(NotificationService.ENV_SUCCESS));
            assertEquals("", delivery.env().get(NotificationService.ENV_MODEL));
            assertEquals("", delivery.env().get(NotificationService.ENV_PROVIDER));
        }
        final var approval = deliveries.getLast().env();
        assertEquals(
                "/btw: Approval required for bash.\nReturn to YCA to review.",
                approval.get(NotificationService.ENV_BODY));
        assertEquals("bash", approval.get(NotificationService.ENV_TOOL));
        assertEquals("btw", approval.get(NotificationService.ENV_SOURCE));
    }

    @Test
    @EnabledOnOs(LINUX)
    void interactionLabelsAreBoundedAndDesktopMarkupEscaped(@TempDir final Path tempDir) {
        final var ref = new AtomicReference<Delivery>();
        final var service = new NotificationService(
                TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true")),
                executor(),
                (argv, env) -> {
                    ref.set(new Delivery(argv, env));
                    return new FakeProcess();
                },
                tempDir);
        service.onApprovalRequired("s", "<b>&\n" + "x".repeat(200), "<tool>&", "btw");
        final var delivery = ref.get();
        assertTrue(delivery.argv().contains("dialog-warning"));
        assertTrue(delivery.argv().get(delivery.argv().size() - 2).startsWith("s - &lt;b&gt;&amp; "));
        assertTrue(delivery.argv().getLast().contains("&lt;tool&gt;&amp;"));
        assertTrue(delivery.env().get(NotificationService.ENV_TITLE).length() <= 124);
    }

    @Test
    void deliveryIsAsynchronousAndFailuresDoNotReachCaller() throws Exception {
        final var release = new CountDownLatch(1);
        final var delivered = new CountDownLatch(1);
        try (final var worker = Executors.newSingleThreadExecutor()) {
            worker.execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            final var service = new NotificationService(
                    TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true")), worker, (argv, env) -> {
                        delivered.countDown();
                        throw new IllegalStateException("unavailable desktop");
                    });
            try {
                service.onInputRequired("s", "title", 1, "main");
                assertEquals(1, delivered.getCount(), "delivery must be queued, not run on the caller");
            } finally {
                release.countDown();
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        final var stopped = Executors.newSingleThreadExecutor();
        stopped.shutdown();
        final var service = new NotificationService(
                TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true")), stopped, (argv, env) -> {
                    throw new AssertionError("must not execute");
                });
        service.onApprovalRequired("s", "title", "bash", "main");
    }

    /** A synchronous executor: execute() runs the task inline so deliveries are observable immediately. */
    private static ExecutorService executor() {
        return new ExecutorService() {
            @Override
            public void execute(final Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(final long timeout, final TimeUnit unit) {
                return true;
            }

            @Override
            public <T> Future<T> submit(final Callable<T> task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Future<T> submit(final Runnable task, final T result) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Future<?> submit(final Runnable task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> List<Future<T>> invokeAll(
                    final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(
                    final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Captures each delivery: the argv and the env, plus the returned process. */
    private record Delivery(List<String> argv, Map<String, String> env) {}

    /** A fake process whose waitFor returns immediately. */
    private static final class FakeProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // no-op
        }
    }

    @Test
    void disabledIsNoOp() {
        final var deliveries = new ArrayList<Delivery>();
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            deliveries.add(new Delivery(argv, env));
            return new FakeProcess();
        };
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.notifications.enabled", "false",
                "yca.notifications.command", "echo"));
        final var service = new NotificationService(config, executor(), exec);
        service.onTurnEnd("s1", "title", "model", "provider", true, "body");
        assertTrue(deliveries.isEmpty(), "disabled notifications must not run the command");
    }

    @Test
    @EnabledOnOs(LINUX)
    void defaultSinkAppendsIconTitleAndBodyAndSetsEnv(@TempDir final Path tempDir) {
        final var deliveryRef = new AtomicReference<Delivery>();
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            deliveryRef.set(new Delivery(argv, env));
            return new FakeProcess();
        };
        final var config = TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true"));
        final var service = new NotificationService(config, executor(), exec, tempDir);
        service.onTurnEnd("s1", "t1", "m1", "p1", true, "b1");
        final var delivery = deliveryRef.get();
        // Linux default sink (notify-send): --app-name=yca, -h x-canonical-private-synchronous, -i <icon>, -n
        // <appIcon>, <title>, <body>.
        final var appIcon = tempDir.resolve("yca.png");
        assertTrue(Files.isRegularFile(appIcon), "app icon must be materialized as a file in the temp dir");
        assertEquals(
                List.of(
                        "notify-send",
                        "--app-name=yca",
                        "-h",
                        "string:x-canonical-private-synchronous:s1",
                        "-i",
                        "emblem-default",
                        "-n",
                        appIcon.toString(),
                        "t1",
                        "b1"),
                delivery.argv());
        assertEquals("s1", delivery.env().get(NotificationService.ENV_SESSION));
        assertEquals("t1", delivery.env().get(NotificationService.ENV_TITLE));
        assertEquals("m1", delivery.env().get(NotificationService.ENV_MODEL));
        assertEquals("p1", delivery.env().get(NotificationService.ENV_PROVIDER));
        assertEquals("true", delivery.env().get(NotificationService.ENV_SUCCESS));
        assertEquals("b1", delivery.env().get(NotificationService.ENV_BODY));
    }

    @Test
    @EnabledOnOs(LINUX)
    void defaultSinkUsesErrorIconOnFailure(@TempDir final Path tempDir) {
        final var deliveryRef = new AtomicReference<Delivery>();
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            deliveryRef.set(new Delivery(argv, env));
            return new FakeProcess();
        };
        final var config = TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true"));
        final var service = new NotificationService(config, executor(), exec, tempDir);
        service.onTurnEnd("s2", "t2", "m2", "p2", false, "b2");
        final var delivery = deliveryRef.get();
        final var appIcon = tempDir.resolve("yca.png");
        assertEquals(
                List.of(
                        "notify-send",
                        "--app-name=yca",
                        "-h",
                        "string:x-canonical-private-synchronous:s2",
                        "-i",
                        "dialog-error",
                        "-n",
                        appIcon.toString(),
                        "t2",
                        "b2"),
                delivery.argv());
        assertEquals("false", delivery.env().get(NotificationService.ENV_SUCCESS));
        assertEquals("b2", delivery.env().get(NotificationService.ENV_BODY));
    }

    @Test
    @EnabledOnOs(LINUX)
    void defaultSinkFallsBackToYcaReplacesIdWhenSessionIsNull(@TempDir final Path tempDir) {
        final var deliveryRef = new AtomicReference<Delivery>();
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            deliveryRef.set(new Delivery(argv, env));
            return new FakeProcess();
        };
        final var config = TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true"));
        final var service = new NotificationService(config, executor(), exec, tempDir);
        service.onTurnEnd(null, "t3", "m3", "p3", true, "b3");
        final var delivery = deliveryRef.get();
        final var appIcon = tempDir.resolve("yca.png");
        // a null session id collapses under the fixed "yca" key, and -n is still applied.
        assertEquals(
                List.of(
                        "notify-send",
                        "--app-name=yca",
                        "-h",
                        "string:x-canonical-private-synchronous:yca",
                        "-i",
                        "emblem-default",
                        "-n",
                        appIcon.toString(),
                        "t3",
                        "b3"),
                delivery.argv());
    }

    @Test
    void customCommandKeepsArgvAndSetsEnv() {
        final var deliveryRef = new AtomicReference<Delivery>();
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            deliveryRef.set(new Delivery(argv, env));
            return new FakeProcess();
        };
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.notifications.enabled",
                "true",
                "yca.notifications.command",
                String.join(",", "remote-notify", "--flag")));
        final var service = new NotificationService(config, executor(), exec);
        service.onTurnEnd("s9", "t9", "m9", "p9", false, "b9");
        final var delivery = deliveryRef.get();
        // the custom command argv must stay exactly as configured (env-only payload)
        assertEquals(List.of("remote-notify", "--flag"), delivery.argv());
        assertEquals("s9", delivery.env().get(NotificationService.ENV_SESSION));
        assertEquals("false", delivery.env().get(NotificationService.ENV_SUCCESS));
        assertEquals("b9", delivery.env().get(NotificationService.ENV_BODY));
    }

    @Test
    void neverThrowsWhenExecFails() {
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            throw new IllegalStateException("boom");
        };
        final var config = TestConfigs.codingAgent(Map.of(
                "yca.notifications.enabled", "true",
                "yca.notifications.command", "echo"));
        final var service = new NotificationService(config, null, exec);
        // must not throw even with no executor and a failing command
        service.onTurnEnd("s1", "t1", "m1", "p1", true, "b1");
    }
}
