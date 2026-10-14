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
package io.yupiik.ia.agent.web;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcEndpoint;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code yca web} command: starts the Tomcat-free HTTP server (the JRE adapter)
 * serving the JSON-RPC API, the SSE stream and the bundled React UI, then opens
 * the default browser. Blocks until shut down.
 */
@DefaultScoped
@Command(name = "web", description = "Start the web UI and JSON-RPC/SSE server on the configured host and port.")
public class WebCommand implements Runnable, AutoCloseable {
    private final WebConf conf;
    private final SseBroker broker;
    private final WebSessionManager sessionManager;
    private final JsonRpcHandler jsonRpc;
    private final JsonMapper mapper;
    private final ScheduledExecutorService scheduler;

    private JreHttpServer server;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WebCommand(
            final WebConf conf,
            final SseBroker broker,
            final WebSessionManager sessionManager,
            final JsonRpcHandler jsonRpc,
            final JsonMapper mapper,
            final ScheduledExecutorService scheduler) {
        this.conf = conf;
        this.broker = broker;
        this.sessionManager = sessionManager;
        this.jsonRpc = jsonRpc;
        this.mapper = mapper;
        this.scheduler = scheduler;
    }

    @Override
    public void run() {
        final var endpoints = List.of(
                new JsonRpcEndpoint(jsonRpc, mapper, "/api/rpc", true),
                new SseExchangeHandler(broker, sessionManager::snapshotFor),
                new StaticAssetsExchangeHandler());
        // when access logging is enabled the logger becomes the single dispatcher in front of the
        // real endpoints: it replicates the JRE matching and logs one CLF line per request
        final List<Endpoint> served = conf.accessLogEnabled() ? List.of(new JreAccessLogger(endpoints)) : endpoints;
        final var http = new JreHttpServer(
                new JreHttpServerConfiguration(conf.host(), conf.port(), conf.virtualThreads()), served);
        this.server = http;
        http.start();

        startPing();
        if (conf.openBrowser()) {
            openBrowser("http://" + http.configuration().host() + ":" + http.port() + "/");
        }
        System.out.println("web UI listening on http://" + http.configuration().host() + ":" + http.port() + "/");
        // The web command must unblock its own await() on shutdown. CliAwaiter creates the command
        // instance via its own try-with-resources Instance (not a container-managed bean), so the
        // container closure triggered by fusion.launcher.useHook does not reach this instance: only
        // this hook (which calls close(), idempotent) stops the server and lets run() return.
        final var hook = new Thread(this::close, "yca-web-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            http.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException ignored) {
                // JVM already shutting down
            }
            // ensure the container bean side (sessions, broker) is released even if this instance
            // is not the container-managed one
            sessionManager.close();
        }
    }

    private void startPing() {
        scheduler.scheduleAtFixedRate(() -> broker.broadcastAll(SseBroker.pingFrame()), 15, 15, TimeUnit.SECONDS);
    }

    private static void openBrowser(final String url) {
        try {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") == null && Desktop.isDesktopSupported()) {
                final var desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(url));
                    return;
                }
            }
            final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final var cmd = os.contains("win")
                    ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                    : os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
            Runtime.getRuntime().exec(cmd);
        } catch (final Throwable ignored) {
            // best-effort: never fail the command because the browser could not open
        }
    }

    @Override
    public void close() {
        // fusion's container also calls close() on @DefaultScoped AutoCloseable beans when the
        // container closes (after CliLauncher.await returns), while the shutdown hook below calls
        // it first on Ctrl+C: make it idempotent so closing twice is harmless.
        if (closed.compareAndSet(false, true)) {
            // release every SSE exchange FIRST: JreHttpServer.close() -> stop(grace) waits briefly
            // for in-flight requests; a parked SSE subscriber is completed here so a graceful SSE
            // stream is closed cleanly, and any truly-stuck exchange is aborted by the same bounded
            // grace instead of hanging the shutdown hook on Ctrl+C.
            broker.close();
            final var http = server;
            if (http != null) {
                http.close(); // unblocks await() and frees the port
            }
            sessionManager.close();
        }
    }

    @RootConfiguration("-")
    public record WebConf(
            @Property(defaultValue = "\"127.0.0.1\"", documentation = "Host the web server binds to.")
            String host,

            @Property(defaultValue = "6060", documentation = "Port the web server binds to.")
            int port,

            @Property(defaultValue = "true", documentation = "Open the default browser on start.")
            boolean openBrowser,

            @Property(
                    defaultValue = "true",
                    documentation =
                            "Use a virtual-thread-per-task executor (the default); when false a plain cached pool is used.")
            boolean virtualThreads,

            @Property(
                    defaultValue = "false",
                    documentation = "Enable Common Log Format access logging: each request is logged at INFO on the "
                            + "io.yupiik.ia.agent.web.JreAccessLogger logger (one line per request).")
            boolean accessLogEnabled) {}
}
