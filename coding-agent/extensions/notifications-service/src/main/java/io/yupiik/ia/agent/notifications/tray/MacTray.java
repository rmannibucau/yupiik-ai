/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AppKit integration for a process whose OS main thread pumps {@link #poll()}.
 * On a JVM, launch with -XstartOnFirstThread and --enable-native-access=ALL-UNNAMED
 * (or enable native access for the containing module).
 * No AppKit or FFM linker initialization happens merely by loading this class.
 *
 * <p>Uses only pointer/scalar Objective-C messages: CGFloat and NSUInteger are
 * double and 64-bit respectively on both supported macOS ABIs. BOOL is passed
 * as a single byte (0 or 1), covering both Intel's signed char and arm64's bool.
 * GraalVM 25 documents macOS FFM upcalls on AArch64, not macOS/x64; the latter
 * therefore requires a JVM or a native-image implementation with that support.</p>
 */
public final class MacTray implements NativeTray {
    // These descriptors must stay in sync with mac/reachability-metadata.json.
    private static final FunctionDescriptor OBJECT = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OBJECT_ARGUMENT = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OBJECT_DOUBLE =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE);
    private static final FunctionDescriptor OBJECT_THREE_ARGUMENTS =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor EVENT =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_BYTE);
    private static final FunctionDescriptor VOID = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_ARGUMENT = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_BYTE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE);
    private static final FunctionDescriptor VOID_LONG = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor BOOL_LONG = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor ACTION = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor IS_MAIN = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor NAMED_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS);
    private static final FunctionDescriptor ALLOCATE_CLASS =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor ADD_METHOD =
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CLASS_VOID = FunctionDescriptor.ofVoid(ADDRESS);
    private static final MethodType ACTION_TYPE =
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
    private static final Map<Long, MacTray> TARGETS = new ConcurrentHashMap<>();

    private final Thread owner = Thread.currentThread();
    private final Cocoa cocoa;
    private volatile MacEventWait eventWait;
    private Cocoa.Pool cleanupPool;
    private MemorySegment application = MemorySegment.NULL; // Shared, not owned.
    private MemorySegment statusBar = MemorySegment.NULL; // Shared, not owned.
    private MemorySegment statusItem = MemorySegment.NULL; // Retained below.
    private MemorySegment menu = MemorySegment.NULL; // alloc/init ownership.
    private MemorySegment acknowledgeItem = MemorySegment.NULL; // Owned by menu.
    private MemorySegment quitItem = MemorySegment.NULL; // Owned by menu.
    private MemorySegment targetClass = MemorySegment.NULL;
    private MemorySegment target = MemorySegment.NULL; // alloc/init ownership.
    private MemorySegment image = MemorySegment.NULL; // alloc/init ownership.
    private boolean statusMenuDetached;
    private boolean statusItemRemoved;
    private boolean unreadAcknowledged;
    private boolean quitRequested;
    private boolean disposed;

    public MacTray(final Path icon) {
        final var os = System.getProperty("os.name", "");
        if (!"Mac OS X".equals(os) && !"Darwin".equals(os)) {
            throw new UnsupportedOperationException("MacTray requires macOS");
        }
        final var architecture = System.getProperty("os.arch", "");
        if (ADDRESS.byteSize() != 8
                || !("aarch64".equals(architecture) || "x86_64".equals(architecture) || "amd64".equals(architecture))) {
            throw new UnsupportedOperationException("MacTray requires 64-bit Intel or Apple Silicon");
        }
        cocoa = new Cocoa();
        try (final var pool = cocoa.pool()) {
            initialize(icon);
            eventWait = new MacEventWait();
        } catch (final RuntimeException | Error failure) {
            try {
                close();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void initialize(final Path icon) {
        application = cocoa.object(cocoa.type("NSApplication"), "sharedApplication");
        // A menu-bar utility, not a Dock application. Never terminate the shared NSApplication.
        if ((byte) cocoa.send(BOOL_LONG, application, "setActivationPolicy:", 1L) == 0) {
            throw new IllegalStateException("Unable to configure an accessory NSApplication");
        }
        cocoa.send(VOID, application, "finishLaunching");

        targetClass =
                cocoa.allocateClass("YCAMacTray_" + UUID.randomUUID().toString().replace("-", ""));
        try {
            final var lookup = MethodHandles.lookup();
            cocoa.addAction(
                    targetClass,
                    "acknowledgeUnread:",
                    lookup.findStatic(MacTray.class, "acknowledgeUnread", ACTION_TYPE));
            cocoa.addAction(targetClass, "quitTray:", lookup.findStatic(MacTray.class, "quitTray", ACTION_TYPE));
        } catch (final NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
        cocoa.registerClass(targetClass);
        target = cocoa.object(targetClass, "new");
        TARGETS.put(target.address(), this);

        menu = cocoa.object(cocoa.type("NSMenu"), "new");
        cocoa.send(VOID_BYTE, menu, "setAutoenablesItems:", (byte) 0);
        acknowledgeItem = addMenuItem("Acknowledge unread", "acknowledgeUnread:");
        quitItem = addMenuItem("Quit", "quitTray:");

        statusBar = cocoa.object(cocoa.type("NSStatusBar"), "systemStatusBar");
        // NSVariableStatusItemLength = -1, a CGFloat rather than a struct.
        statusItem = required((MemorySegment) cocoa.send(OBJECT_DOUBLE, statusBar, "statusItemWithLength:", -1d));
        cocoa.object(statusItem, "retain");
        cocoa.send(VOID_ARGUMENT, statusItem, "setMenu:", menu);
        image = required((MemorySegment) cocoa.send(
                OBJECT_ARGUMENT,
                cocoa.object(cocoa.type("NSImage"), "alloc"),
                "initWithContentsOfFile:",
                cocoa.string(icon.toAbsolutePath().toString())));
        final var button = cocoa.object(statusItem, "button");
        cocoa.send(VOID_ARGUMENT, button, "setImage:", image);
        // Scale the existing 64px app icon down to the menu-bar button instead of loading AWT image code.
        cocoa.send(VOID_LONG, button, "setImageScaling:", 0L);
        update("YCA notifications", false);
    }

    private MemorySegment addMenuItem(final String title, final String action) {
        final var allocated = cocoa.object(cocoa.type("NSMenuItem"), "alloc");
        final var item = required((MemorySegment) cocoa.send(
                OBJECT_THREE_ARGUMENTS,
                allocated,
                "initWithTitle:action:keyEquivalent:",
                cocoa.string(title),
                cocoa.selector(action),
                cocoa.string("")));
        try {
            cocoa.send(VOID_ARGUMENT, item, "setTarget:", target);
            cocoa.send(VOID_ARGUMENT, menu, "addItem:", item);
            return item;
        } finally {
            // The menu retains the item. Keep only a borrowed pointer for detaching its target.
            cocoa.send(VOID, item, "release");
        }
    }

    @Override
    public void update(final String tooltip, final boolean attention) {
        checkThread();
        if (closed()) {
            return;
        }
        try (final var pool = cocoa.pool()) {
            final var button = cocoa.object(statusItem, "button");
            cocoa.send(VOID_ARGUMENT, button, "setTitle:", cocoa.string(attention ? "!" : ""));
            cocoa.send(VOID_BYTE, button, "setHighlighted:", attention ? (byte) 1 : (byte) 0);
            cocoa.send(
                    VOID_ARGUMENT, button, "setToolTip:", tooltip == null ? MemorySegment.NULL : cocoa.string(tooltip));
        }
    }

    @Override
    public void poll() {
        checkThread();
        if (closed()) {
            return;
        }
        try (final var pool = cocoa.pool()) {
            final var deadline = cocoa.object(cocoa.type("NSDate"), "distantPast");
            // Bound the batch and never wait for an event. sendEvent: performs normal AppKit
            // dispatch, including AppKit's own nested tracking loop while a menu is open.
            for (var i = 0; i < 64 && !quitRequested; i++) {
                final var event = (MemorySegment) cocoa.send(
                        EVENT,
                        application,
                        "nextEventMatchingMask:untilDate:inMode:dequeue:",
                        -1L,
                        deadline,
                        cocoa.defaultMode,
                        (byte) 1);
                if (event.address() == 0) {
                    break;
                }
                cocoa.send(VOID_ARGUMENT, application, "sendEvent:", event);
            }
            cocoa.send(VOID, application, "updateWindows");
        }
    }

    @Override
    public void await(final long timeoutMillis) {
        checkThread();
        if (!closed()) {
            eventWait.await(timeoutMillis);
        }
    }

    @Override
    public void wakeup() {
        final var wait = eventWait;
        if (wait != null) {
            wait.wakeup();
        }
    }

    @Override
    public boolean acknowledged() {
        checkThread();
        final var result = unreadAcknowledged;
        unreadAcknowledged = false;
        return result;
    }

    @Override
    public boolean closed() {
        checkThread();
        return disposed || quitRequested;
    }

    @Override
    public void close() {
        checkThread();
        if (disposed) {
            return;
        }
        quitRequested = true;
        Throwable failure = cleanup(null, () -> {
            if (eventWait != null) {
                eventWait.close();
            }
        });
        failure = cleanup(failure, () -> {
            if (cleanupPool == null) {
                cleanupPool = cocoa.pool();
            }
        });
        if (cleanupPool != null) {
            if (acknowledgeItem.address() != 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID_ARGUMENT, acknowledgeItem, "setTarget:", MemorySegment.NULL);
                    acknowledgeItem = MemorySegment.NULL;
                });
            }
            if (quitItem.address() != 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID_ARGUMENT, quitItem, "setTarget:", MemorySegment.NULL);
                    quitItem = MemorySegment.NULL;
                });
            }
            if (statusItem.address() != 0) {
                if (!statusMenuDetached) {
                    failure = cleanup(failure, () -> {
                        cocoa.send(VOID_ARGUMENT, statusItem, "setMenu:", MemorySegment.NULL);
                        statusMenuDetached = true;
                    });
                }
                if (!statusItemRemoved) {
                    failure = cleanup(failure, () -> {
                        cocoa.send(VOID_ARGUMENT, statusBar, "removeStatusItem:", statusItem);
                        statusItemRemoved = true;
                    });
                }
                if (statusMenuDetached && statusItemRemoved) {
                    failure = cleanup(failure, () -> {
                        cocoa.send(VOID, statusItem, "release");
                        statusItem = MemorySegment.NULL;
                    });
                }
            }
            // Keep borrowed item pointers valid until every target and the status menu are detached.
            if (menu.address() != 0
                    && acknowledgeItem.address() == 0
                    && quitItem.address() == 0
                    && statusItem.address() == 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID, menu, "release");
                    menu = MemorySegment.NULL;
                });
            }
            if (image.address() != 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID, image, "release");
                    image = MemorySegment.NULL;
                });
            }
            if (target.address() != 0
                    && menu.address() == 0
                    && statusItem.address() == 0
                    && acknowledgeItem.address() == 0
                    && quitItem.address() == 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID, target, "release");
                    TARGETS.remove(target.address());
                    target = MemorySegment.NULL;
                });
            }
            failure = cleanup(failure, () -> {
                cleanupPool.close();
                cleanupPool = null;
            });
        }
        // Drain autoreleases successfully before disposing the class, then its IMP stubs.
        // On failure keep the arena alive and the remaining handles available for a later close.
        if (failure == null && target.address() == 0 && targetClass.address() != 0) {
            failure = cleanup(null, () -> {
                cocoa.disposeClass(targetClass);
                targetClass = MemorySegment.NULL;
            });
        }
        if (failure == null && targetClass.address() == 0) {
            failure = cleanup(null, () -> {
                cocoa.close();
                disposed = true;
            });
        }
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

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("MacTray is confined to its creating OS main thread");
        }
    }

    private static MemorySegment required(final MemorySegment object) {
        if (object.address() == 0) {
            throw new IllegalStateException("AppKit/Objective-C returned nil");
        }
        return object;
    }

    // These callbacks only record requests. In particular, acknowledging never approves a prompt.
    // No exception may unwind through an FFM upcall into Objective-C.
    private static void acknowledgeUnread(
            final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null) {
                tray.unreadAcknowledged = true;
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static void quitTray(final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null) {
                tray.quitRequested = true;
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static final class Cocoa implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final Linker linker;
        private final SymbolLookup objc;
        private final MemorySegment message;
        private final MemorySegment defaultMode;
        private final MethodHandle getClass;
        private final MethodHandle registerSelector;
        private final MethodHandle allocateClass;
        private final MethodHandle addMethod;
        private final MethodHandle registerClass;
        private final MethodHandle disposeClass;
        private final Map<FunctionDescriptor, MethodHandle> messages = new HashMap<>();
        private final Map<String, MemorySegment> selectors = new HashMap<>();

        private Cocoa() {
            try {
                linker = Linker.nativeLinker();
                final var system = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", arena);
                final var isMain = linker.downcallHandle(system.findOrThrow("pthread_main_np"), IS_MAIN);
                if ((int) invoke(isMain) == 0) {
                    throw new IllegalStateException(
                            "Construct MacTray on the OS main thread (JVM: -XstartOnFirstThread)");
                }
                SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", arena);
                final var foundation =
                        SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena);
                defaultMode = required(foundation
                        .findOrThrow("NSDefaultRunLoopMode")
                        .reinterpret(ADDRESS.byteSize())
                        .get(ADDRESS, 0));
                objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
                message = objc.findOrThrow("objc_msgSend");
                getClass = function("objc_getClass", NAMED_POINTER);
                registerSelector = function("sel_registerName", NAMED_POINTER);
                allocateClass = function("objc_allocateClassPair", ALLOCATE_CLASS);
                addMethod = function("class_addMethod", ADD_METHOD);
                registerClass = function("objc_registerClassPair", CLASS_VOID);
                disposeClass = function("objc_disposeClassPair", CLASS_VOID);
            } catch (final RuntimeException | Error failure) {
                try {
                    arena.close();
                } catch (final RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private MethodHandle function(final String name, final FunctionDescriptor descriptor) {
            return linker.downcallHandle(objc.findOrThrow(name), descriptor);
        }

        private MemorySegment type(final String name) {
            try (final var strings = Arena.ofConfined()) {
                return required((MemorySegment) invoke(getClass, strings.allocateFrom(name)));
            }
        }

        private MemorySegment selector(final String name) {
            return selectors.computeIfAbsent(name, key -> {
                try (final var strings = Arena.ofConfined()) {
                    return required((MemorySegment) invoke(registerSelector, strings.allocateFrom(key)));
                }
            });
        }

        private Object send(
                final FunctionDescriptor descriptor,
                final MemorySegment receiver,
                final String selector,
                final Object... arguments) {
            final var parameters = new Object[arguments.length + 2];
            parameters[0] = receiver;
            parameters[1] = selector(selector);
            System.arraycopy(arguments, 0, parameters, 2, arguments.length);
            return invoke(messages.computeIfAbsent(descriptor, key -> linker.downcallHandle(message, key)), parameters);
        }

        private MemorySegment object(final MemorySegment receiver, final String selector) {
            return required((MemorySegment) send(OBJECT, receiver, selector));
        }

        private MemorySegment string(final String value) {
            try (final var strings = Arena.ofConfined()) {
                return required((MemorySegment)
                        send(OBJECT_ARGUMENT, type("NSString"), "stringWithUTF8String:", strings.allocateFrom(value)));
            }
        }

        private MemorySegment allocateClass(final String name) {
            try (final var strings = Arena.ofConfined()) {
                return required(
                        (MemorySegment) invoke(allocateClass, type("NSObject"), strings.allocateFrom(name), 0L));
            }
        }

        private void addAction(final MemorySegment type, final String selector, final MethodHandle callback) {
            try (final var strings = Arena.ofConfined()) {
                final var stub = linker.upcallStub(callback, ACTION, arena);
                if ((byte) invoke(addMethod, type, selector(selector), stub, strings.allocateFrom("v@:@")) == 0) {
                    throw new IllegalStateException("Unable to install Objective-C action " + selector);
                }
            }
        }

        private void registerClass(final MemorySegment type) {
            invoke(registerClass, type);
        }

        private void disposeClass(final MemorySegment type) {
            invoke(disposeClass, type);
        }

        private Pool pool() {
            return new Pool(object(type("NSAutoreleasePool"), "new"));
        }

        @Override
        public void close() {
            arena.close();
        }

        private final class Pool implements AutoCloseable {
            private final MemorySegment pool;

            private Pool(final MemorySegment pool) {
                this.pool = pool;
            }

            @Override
            public void close() {
                send(VOID, pool, "drain");
            }
        }

        private static Object invoke(final MethodHandle handle, final Object... arguments) {
            try {
                return handle.invokeWithArguments(arguments);
            } catch (final RuntimeException | Error failure) {
                throw failure;
            } catch (final Throwable failure) {
                throw new IllegalStateException("Objective-C/FFM invocation failed", failure);
            }
        }
    }
}
