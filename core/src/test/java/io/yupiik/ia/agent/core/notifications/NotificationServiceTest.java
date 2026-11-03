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

import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class NotificationServiceTest {

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
            public java.util.List<Runnable> shutdownNow() {
                return java.util.List.of();
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
            public boolean awaitTermination(final long timeout, final java.util.concurrent.TimeUnit unit) {
                return true;
            }

            @Override
            public <T> java.util.concurrent.Future<T> submit(final java.util.concurrent.Callable<T> task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> java.util.concurrent.Future<T> submit(final Runnable task, final T result) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.concurrent.Future<?> submit(final Runnable task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
                    final java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
                    final java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                    final long timeout,
                    final java.util.concurrent.TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(final java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T invokeAny(
                    final java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                    final long timeout,
                    final java.util.concurrent.TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Captures each delivery: the argv and the env, plus the returned process. */
    private record Delivery(List<String> argv, Map<String, String> env) {}

    /** A fake process whose waitFor returns immediately. */
    private static final class FakeProcess extends Process {
        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
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
    void defaultSinkAppendsTitleAndBodyAndSetsEnv() {
        final var deliveryRef = new AtomicReference<Delivery>();
        final var exec = (NotificationService.ExecFn) (argv, env) -> {
            deliveryRef.set(new Delivery(argv, env));
            return new FakeProcess();
        };
        final var config = TestConfigs.codingAgent(Map.of("yca.notifications.enabled", "true"));
        final var service = new NotificationService(config, executor(), exec);
        service.onTurnEnd("s1", "t1", "m1", "p1", true, "b1");
        final var delivery = deliveryRef.get();
        // default sink on this test JVM is resolved at runtime by os.name; assert the trailing title+body are there
        assertTrue(delivery.argv().size() >= 2, "default sink must receive title and body args");
        assertEquals("t1", delivery.argv().get(delivery.argv().size() - 2));
        assertEquals("b1", delivery.argv().get(delivery.argv().size() - 1));
        assertEquals("s1", delivery.env().get(NotificationService.ENV_SESSION));
        assertEquals("t1", delivery.env().get(NotificationService.ENV_TITLE));
        assertEquals("m1", delivery.env().get(NotificationService.ENV_MODEL));
        assertEquals("p1", delivery.env().get(NotificationService.ENV_PROVIDER));
        assertEquals("true", delivery.env().get(NotificationService.ENV_SUCCESS));
        assertEquals("b1", delivery.env().get(NotificationService.ENV_BODY));
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
