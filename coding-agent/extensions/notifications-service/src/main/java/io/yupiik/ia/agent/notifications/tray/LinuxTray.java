/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** GTK3/AppIndicator integration; no AWT, X11 calls or Java native bridge dependency. */
public final class LinuxTray implements NativeTray {
    private static final FunctionDescriptor INT_POINTER_POINTER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER_POINTER_INT =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor POINTER = FunctionDescriptor.of(ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS);
    private static final FunctionDescriptor POINTER_INT = FunctionDescriptor.of(ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_INT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_POINTER = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_POINTER = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor INT = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor INT_INT = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor INT_POINTER = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor INT_POINTER_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_POINTER_POINTER_POINTER =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor LONG_POINTER_POINTER_POINTER_POINTER_POINTER_INT =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodType ACTION_TYPE =
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class);
    private static final MethodType TICK_TYPE = MethodType.methodType(int.class, MemorySegment.class);
    private static final MethodHandle ACKNOWLEDGE;
    private static final MethodHandle QUIT;
    private static final MethodHandle TICK;

    static {
        try {
            final var lookup = MethodHandles.lookup();
            ACKNOWLEDGE = lookup.findStatic(LinuxTray.class, "acknowledge", ACTION_TYPE);
            QUIT = lookup.findStatic(LinuxTray.class, "quit", ACTION_TYPE);
            TICK = lookup.findStatic(LinuxTray.class, "tick", TICK_TYPE);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static LinuxTray active;
    private final Arena arena = Arena.ofShared();
    private final Linker linker = Linker.nativeLinker();
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private SymbolLookup gtk;
    private SymbolLookup indicatorLibrary;
    private MethodHandle wakeup;
    private MemorySegment context = MemorySegment.NULL;
    private MemorySegment tickStub = MemorySegment.NULL;
    private MemorySegment timeoutSource = MemorySegment.NULL;
    private boolean timeoutSourceDestroyed;
    private MemorySegment indicator = MemorySegment.NULL;
    private MemorySegment menu = MemorySegment.NULL;
    private MemorySegment heading = MemorySegment.NULL;
    private final String iconPath;
    private boolean menuDestroyed;
    private boolean acknowledged;
    private boolean closed;

    public LinuxTray(final Path icon) {
        iconPath = icon.toAbsolutePath().toString();
        try {
            gtk = SymbolLookup.libraryLookup("libgtk-3.so.0", arena);
            try {
                indicatorLibrary = SymbolLookup.libraryLookup("libayatana-appindicator3.so.1", arena);
            } catch (final IllegalArgumentException missingAyatana) {
                indicatorLibrary = SymbolLookup.libraryLookup("libappindicator3.so.1", arena);
            }
            if ((int) call(gtk, "gtk_init_check", INT_POINTER_POINTER, MemorySegment.NULL, MemorySegment.NULL) == 0) {
                throw new IllegalStateException(
                        "GTK cannot connect to a desktop display; run serve in the graphical session.");
            }
            if (active != null) {
                throw new IllegalStateException("A Linux tray is already active in this process.");
            }
            active = this;
            context = (MemorySegment) call(gtk, "g_main_context_default", POINTER);
            // Resolve once on the owner thread: wakeup must never access the owner-confined function cache.
            wakeup = linker.downcallHandle(
                    gtk.find("g_main_context_wakeup")
                            .orElseThrow(
                                    () -> new IllegalStateException("Missing native symbol: g_main_context_wakeup")),
                    VOID_POINTER);
            tickStub = linker.upcallStub(TICK, INT_POINTER, arena);
            indicator = (MemorySegment) call(
                    indicatorLibrary,
                    "app_indicator_new",
                    POINTER_POINTER_POINTER_INT,
                    arena.allocateFrom("yca-notifications"),
                    arena.allocateFrom(iconPath),
                    0);
            if (indicator.address() == 0) {
                throw new IllegalStateException("AppIndicator creation failed.");
            }
            menu = (MemorySegment) call(gtk, "gtk_menu_new", POINTER);
            call(gtk, "g_object_ref_sink", POINTER_POINTER, menu);
            heading = item("YCA - no unread activity", null);
            call(gtk, "gtk_widget_set_sensitive", VOID_POINTER_INT, heading, 0);
            item("Acknowledge unread activity (not approval)", ACKNOWLEDGE);
            item("Quit notifications service", QUIT);
            call(gtk, "gtk_widget_show_all", VOID_POINTER, menu);
            call(indicatorLibrary, "app_indicator_set_menu", VOID_POINTER_POINTER, indicator, menu);
            call(
                    indicatorLibrary,
                    "app_indicator_set_attention_icon",
                    VOID_POINTER_POINTER,
                    indicator,
                    arena.allocateFrom("dialog-warning"));
            call(indicatorLibrary, "app_indicator_set_status", VOID_POINTER_INT, indicator, 1);
        } catch (final RuntimeException | Error failure) {
            try {
                close();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public void update(final String tooltip, final boolean attention) {
        try (final var strings = Arena.ofConfined()) {
            call(gtk, "gtk_menu_item_set_label", VOID_POINTER_POINTER, heading, strings.allocateFrom(tooltip));
            call(
                    indicatorLibrary,
                    "app_indicator_set_title",
                    VOID_POINTER_POINTER,
                    indicator,
                    strings.allocateFrom(tooltip));
            call(
                    indicatorLibrary,
                    "app_indicator_set_icon",
                    VOID_POINTER_POINTER,
                    indicator,
                    strings.allocateFrom(attention ? "dialog-warning" : iconPath));
            call(indicatorLibrary, "app_indicator_set_status", VOID_POINTER_INT, indicator, attention ? 2 : 1);
        }
    }

    @Override
    public void poll() {
        // Bound dispatch work so a busy desktop cannot starve mailbox processing or shutdown.
        for (int i = 0; i < 64 && (int) call(gtk, "gtk_events_pending", INT) != 0; i++) {
            call(gtk, "gtk_main_iteration_do", INT_INT, 0);
        }
    }

    @Override
    public void await(final long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        if (closed) {
            return;
        }
        Throwable failure = null;
        try {
            if (timeoutMillis > 0) {
                // GLib takes an unsigned 32-bit millisecond interval; never wrap a long timeout.
                timeoutSource = (MemorySegment)
                        call(gtk, "g_timeout_source_new", POINTER_INT, (int) Math.min(timeoutMillis, 0xffff_ffffL));
                timeoutSourceDestroyed = false;
                call(
                        gtk,
                        "g_source_set_callback",
                        VOID_POINTER_POINTER_POINTER_POINTER,
                        timeoutSource,
                        tickStub,
                        MemorySegment.NULL,
                        MemorySegment.NULL);
                call(gtk, "g_source_attach", INT_POINTER_POINTER, timeoutSource, context);
            }
            // Do not hold lifecycleLock here: publishers must be able to wake a blocked iteration.
            // GLib also preserves a wakeup issued before this iteration starts.
            call(gtk, "g_main_context_iteration", INT_POINTER_INT, context, 1);
        } catch (final RuntimeException | Error e) {
            failure = e;
        } finally {
            failure = cleanupTimeoutSource(failure);
        }
        rethrow(failure);
    }

    @Override
    public void wakeup() {
        lifecycleLock.lock();
        try {
            if (closed || !arena.scope().isAlive() || context.address() == 0 || wakeup == null) {
                return;
            }
            try {
                wakeup.invokeExact(context);
            } catch (final RuntimeException | Error e) {
                throw e;
            } catch (final Throwable e) {
                throw new IllegalStateException("Native call failed: g_main_context_wakeup", e);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public boolean acknowledged() {
        final var result = acknowledged;
        acknowledged = false;
        return result;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (!arena.scope().isAlive()) {
                return;
            }
            closed = true;
            Throwable failure = cleanupTimeoutSource(null);
            if (indicator.address() != 0) {
                failure = cleanup(
                        failure,
                        () -> call(indicatorLibrary, "app_indicator_set_status", VOID_POINTER_INT, indicator, 0));
                failure = cleanup(failure, () -> {
                    call(gtk, "g_object_unref", VOID_POINTER, indicator);
                    indicator = MemorySegment.NULL;
                });
            }
            if (menu.address() != 0) {
                if (!menuDestroyed) {
                    failure = cleanup(failure, () -> {
                        call(gtk, "gtk_widget_destroy", VOID_POINTER, menu);
                        menuDestroyed = true;
                    });
                }
                if (menuDestroyed) {
                    failure = cleanup(failure, () -> {
                        call(gtk, "g_object_unref", VOID_POINTER, menu);
                        menu = MemorySegment.NULL;
                    });
                }
            }
            // Keep callback stubs and the singleton alive while native callback objects may survive.
            if (indicator.address() == 0 && menu.address() == 0 && timeoutSource.address() == 0) {
                failure = cleanup(failure, arena::close);
                if (active == this && !arena.scope().isAlive()) {
                    active = null;
                }
            }
            rethrow(failure);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private Throwable cleanupTimeoutSource(final Throwable failure) {
        var result = failure;
        if (timeoutSource.address() != 0) {
            if (!timeoutSourceDestroyed) {
                result = cleanup(result, () -> {
                    call(gtk, "g_source_destroy", VOID_POINTER, timeoutSource);
                    timeoutSourceDestroyed = true;
                });
            }
            if (timeoutSourceDestroyed) {
                result = cleanup(result, () -> {
                    call(gtk, "g_source_unref", VOID_POINTER, timeoutSource);
                    timeoutSource = MemorySegment.NULL;
                });
            }
        }
        return result;
    }

    private static void rethrow(final Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable cleanup(final Throwable failure, final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                return cleanupFailure;
            }
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        return failure;
    }

    private MemorySegment item(final String text, final MethodHandle callback) {
        final var widget =
                (MemorySegment) call(gtk, "gtk_menu_item_new_with_label", POINTER_POINTER, arena.allocateFrom(text));
        call(gtk, "gtk_menu_shell_append", VOID_POINTER_POINTER, menu, widget);
        if (callback != null) {
            final var stub = linker.upcallStub(callback, VOID_POINTER_POINTER, arena);
            call(
                    gtk,
                    "g_signal_connect_data",
                    LONG_POINTER_POINTER_POINTER_POINTER_POINTER_INT,
                    widget,
                    arena.allocateFrom("activate"),
                    stub,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    0);
        }
        return widget;
    }

    private static void acknowledge(final MemorySegment widget, final MemorySegment data) {
        if (active != null) {
            active.acknowledged = true;
        }
    }

    private static void quit(final MemorySegment widget, final MemorySegment data) {
        if (active != null) {
            active.closed = true;
        }
    }

    private static int tick(final MemorySegment data) {
        return 0;
    }

    private Object call(
            final SymbolLookup library, final String name, final FunctionDescriptor descriptor, final Object... args) {
        try {
            final var handle = functions.computeIfAbsent(
                    name,
                    key -> linker.downcallHandle(
                            library.find(key)
                                    .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + key)),
                            descriptor));
            return handle.invokeWithArguments(args);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("Native call failed: " + name, e);
        }
    }
}
