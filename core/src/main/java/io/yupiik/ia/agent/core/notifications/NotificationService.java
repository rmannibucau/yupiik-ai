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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fires an OS notification when a turn ends, opt-in via {@code yca.notifications.enabled} (disabled by
 * default). A custom {@code yca.notifications.command} replaces the built-in OS integration and receives the
 * payload as environment variables; otherwise a best-effort default sink per OS is used. Every path is async
 * (run on the shared executor) and swallows failures so a notification can never fail or slow a turn.
 *
 * <p>The runtime command is resolved once ({@link #defaultSink()} lazily at first delivery) and then the
 * resolved executable is used directly on every later delivery - no per-call OS detection. The custom command
 * is taken verbatim from the configuration; the default sink derives the OS tool and always appends
 * {@code <title> <body>} as positional arguments.</p>
 */
@ApplicationScoped
public class NotificationService {
    private static final Logger LOGGER = Logger.getLogger(NotificationService.class.getName());

    public static final String ENV_SESSION = "YCA_NOTIFICATION_SESSION";
    public static final String ENV_TITLE = "YCA_NOTIFICATION_TITLE";
    public static final String ENV_MODEL = "YCA_NOTIFICATION_MODEL";
    public static final String ENV_PROVIDER = "YCA_NOTIFICATION_PROVIDER";
    public static final String ENV_SUCCESS = "YCA_NOTIFICATION_SUCCESS";
    public static final String ENV_BODY = "YCA_NOTIFICATION_BODY";

    private final boolean enabled;
    private final boolean custom;
    private final String[] sink;
    private final ExecutorService executor;
    private final ExecFn exec;

    @FunctionalInterface
    protected interface ExecFn {
        Process start(List<String> command, Map<String, String> env) throws Exception;
    }

    public NotificationService(final CodingAgentConfiguration configuration, final ExecutorService executor) {
        this(configuration, executor, NotificationService::processBuilderStart);
    }

    protected NotificationService(
            final CodingAgentConfiguration configuration, final ExecutorService executor, final ExecFn exec) {
        final var notifications =
                configuration == null || configuration.notifications() == null ? null : configuration.notifications();
        this.enabled = notifications != null && notifications.enabled();
        final var cfgCommand =
                notifications == null || notifications.command() == null ? null : notifications.command();
        this.custom = cfgCommand != null && !cfgCommand.isEmpty();
        this.sink = custom ? cfgCommand.toArray(String[]::new) : defaultSink();
        this.executor = executor;
        this.exec = exec;
    }

    /**
     * Sends a turn-end notification, best-effort and asynchronous. No-op when notifications are disabled, and
     * never throws: any failure is logged and dropped, so the turn is never impacted.
     */
    public void onTurnEnd(
            final String sessionId,
            final String title,
            final String model,
            final String provider,
            final boolean success,
            final String body) {
        if (!enabled || sink == null) {
            return;
        }
        final var env = env(sessionId, title, model, provider, success, body);
        final List<String> argv;
        if (custom) {
            // the custom command receives the payload via env only, its argv stays as configured.
            argv = List.of(sink);
        } else {
            // the default sink takes <-i <icon>> <title> <body> as positional args, the icon reflecting the
            // turn status (success/error). The names are desktop stock icons and stay fixed: the custom-command
            // path above is the supported override for full configurability.
            final var icon = success ? "emblem-default" : "dialog-error";
            final var args = new String[sink.length + 4]; // sink + (-i icon) + title + body
            System.arraycopy(sink, 0, args, 0, sink.length);
            args[sink.length] = "-i";
            args[sink.length + 1] = icon;
            args[sink.length + 2] = title == null ? "" : title;
            args[sink.length + 3] = body == null ? "" : body;
            argv = List.of(args);
        }
        try {
            if (executor == null) {
                runAndSwallow(() -> run(argv, env));
            } else {
                executor.execute(() -> runAndSwallow(() -> run(argv, env)));
            }
        } catch (final Throwable e) {
            LOGGER.log(Level.FINE, "notification could not be scheduled", e);
        }
    }

    private void run(final List<String> argv, final Map<String, String> env) {
        try {
            final var process = exec.start(argv, env);
            if (process != null) {
                try {
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroy();
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (final Throwable e) {
            LOGGER.log(Level.FINE, "notification could not be delivered", e);
        }
    }

    private static void runAndSwallow(final Runnable task) {
        try {
            task.run();
        } catch (final Throwable e) {
            // best-effort: never surface a notification failure, but keep it debuggable
            LOGGER.log(Level.FINEST, "notification run failed", e);
        }
    }

    private static Map<String, String> env(
            final String sessionId,
            final String title,
            final String model,
            final String provider,
            final boolean success,
            final String body) {
        return Map.of(
                ENV_SESSION, nullToEmpty(sessionId),
                ENV_TITLE, nullToEmpty(title),
                ENV_MODEL, nullToEmpty(model),
                ENV_PROVIDER, nullToEmpty(provider),
                ENV_SUCCESS, Boolean.toString(success),
                ENV_BODY, nullToEmpty(body));
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static Process processBuilderStart(final List<String> argv, final Map<String, String> env)
            throws Exception {
        final var builder = new ProcessBuilder(argv);
        builder.environment().putAll(env);
        return builder.start();
    }

    /**
     * Resolves the default OS sink tool, most specific first. Computed once at construction and reused on every
     * delivery - no per-call OS detection. Native image and headless JVM paths use the portable OS process tool
     * (AWT {@code SystemTray} is skipped there, matching the clipboard's native-image guard).
     */
    private static String[] defaultSink() {
        final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new String[] {"powershell.exe"};
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new String[] {"osascript", "-e", "display notification"};
        }
        return new String[] {"notify-send", "--app-name=yca"};
    }
}
