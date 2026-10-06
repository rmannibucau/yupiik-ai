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
package io.yupiik.ia.agent.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.logging.jul.formatter.InlineFormatter;
import io.yupiik.logging.jul.handler.StandardHandler;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Unit-level coverage of the default inline JUL configuration. The container wiring
 * itself (Start/Stop events, yca.logging.auto precedence, single-install) is covered
 * by the headless TUI tests; here the code paths are exercised directly on the
 * JVM-wide root logger, which is why all tests share the {@code io.yupiik-jul-handler}
 * lock with the TUI suite (the TUI detaches/reattaches the very same handler).
 */
@ResourceLock("io.yupiik-jul-handler")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AutoLogConfigurerTest {
    @Test
    void installsInlineRootHandlerByDefault() {
        final var configurer = new AutoLogConfigurer(TestConfigs.codingAgent(Map.of()));
        try {
            configurer.onStart(new Start());
            final var inline = configurer.installedInlineHandler();
            assertNotNull(inline, "the inline handler must be registered after the Start event");
            assertTrue(inline instanceof StandardHandler, "it must be the yupiik standard handler");
            assertTrue(inline.getFormatter() instanceof InlineFormatter, "it must use the inline formatter");
            assertEquals(Level.INFO, inline.getLevel(), "captured records must start at INFO");
            assertEquals(Level.INFO, Logger.getLogger("").getLevel(), "the root logger level must be INFO");
            assertTrue(
                    contains(Logger.getLogger("").getHandlers(), inline),
                    "the inline handler must be attached to the root logger");
        } finally {
            configurer.onStop(new Stop());
        }
        assertNull(configurer.installedInlineHandler(), "the Stop listener must clear the handler");
    }

    @Test
    void stopRestoresTheRootLoggerHandlers() {
        final var configurer = new AutoLogConfigurer(TestConfigs.codingAgent(Map.of()));
        configurer.onStart(new Start());
        final var inline = configurer.installedInlineHandler();
        assertNotNull(inline);
        configurer.onStop(new Stop());
        assertFalse(
                contains(Logger.getLogger("").getHandlers(), inline),
                "the Stop listener must detach the inline handler from the root logger");
        assertNull(configurer.installedInlineHandler());
    }

    @Test
    void leavesTheRootLoggerAloneWhenAutoIsDisabled() {
        final var configurer = new AutoLogConfigurer(TestConfigs.codingAgent(Map.of("yca.logging.auto", "false")));
        try {
            configurer.onStart(new Start());
            assertNull(configurer.installedInlineHandler(), "auto=false must skip the installation");
            assertFalse(
                    containsStandardHandler(Logger.getLogger("").getHandlers()),
                    "no yupiik handler may be attached when auto is disabled");
        } finally {
            configurer.onStop(new Stop());
        }
    }

    @Test
    void leavesTheRootLoggerAloneWhenAConfigFileIsSet() {
        final var previous = System.getProperty("java.util.logging.config.file");
        System.setProperty("java.util.logging.config.file", "/tmp/yca-jul-test.properties");
        final var configurer = new AutoLogConfigurer(TestConfigs.codingAgent(Map.of()));
        try {
            configurer.onStart(new Start());
            assertNull(
                    configurer.installedInlineHandler(),
                    "an explicit java.util.logging.config.file must keep the JDK defaults");
            assertFalse(containsStandardHandler(Logger.getLogger("").getHandlers()));
        } finally {
            configurer.onStop(new Stop());
            if (previous == null) {
                System.clearProperty("java.util.logging.config.file");
            } else {
                System.setProperty("java.util.logging.config.file", previous);
            }
        }
    }

    @Test
    void replacesTheJdkConsoleHandlerSoRecordsArePrintedOnce() {
        final var root = Logger.getLogger("");
        final var console = new ConsoleHandler();
        root.addHandler(console);
        final var configurer = new AutoLogConfigurer(TestConfigs.codingAgent(Map.of()));
        try {
            configurer.onStart(new Start());
            assertFalse(
                    contains(root.getHandlers(), console),
                    "the JDK default ConsoleHandler must be removed when the inline handler is installed");
            assertTrue(
                    containsStandardHandler(root.getHandlers()),
                    "the yupiik inline handler must replace the JDK ConsoleHandler");
        } finally {
            configurer.onStop(new Stop());
            root.removeHandler(console);
        }
    }

    private static boolean contains(final Handler[] handlers, final Handler expected) {
        for (final var handler : handlers) {
            if (handler == expected) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsStandardHandler(final Handler[] handlers) {
        for (final var handler : handlers) {
            if (handler instanceof StandardHandler) {
                return true;
            }
        }
        return false;
    }
}
