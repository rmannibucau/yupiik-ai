/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox.helper;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Linux-only libc calls. Only a disposable helper process may call {@link #exec}. */
@SuppressWarnings("restricted")
public final class LinuxNative {
    private static final FunctionDescriptor PRCTL =
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final FunctionDescriptor EXECVE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor EXIT = FunctionDescriptor.ofVoid(JAVA_INT);
    private static final FunctionDescriptor WRITE = FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = CALL_STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final MemoryLayout FPROG =
            MemoryLayout.structLayout(JAVA_SHORT, MemoryLayout.paddingLayout(6), ADDRESS);
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle PRCTL_CALL = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("prctl"),
            PRCTL,
            Linker.Option.firstVariadicArg(1),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle EXECVE_CALL = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("execve"), EXECVE, Linker.Option.captureCallState("errno"));
    private static final MethodHandle WRITE_CALL =
            LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("write"), WRITE);
    private static final MethodHandle EXIT_CALL =
            LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("_exit"), EXIT);

    private LinuxNative() {
        // No instances.
    }

    public static void protectBridge() throws Throwable {
        requireArchitecture();
        try (final var arena = Arena.ofConfined()) {
            final var state = arena.allocate(CALL_STATE);
            if ((int) PRCTL_CALL.invokeExact(state, 4, 0L, 0L, 0L, 0L) != 0) {
                throw new IllegalStateException(
                        "prctl(PR_SET_DUMPABLE, 0) failed, errno=" + state.get(JAVA_INT, ERRNO_OFFSET));
            }
        }
    }

    /** Replaces this JVM, never starts a post-filter subprocess. Does not return on success. */
    public static void exec(final List<String> command, final Map<String, String> environment) throws Throwable {
        final var x86 = requireArchitecture();
        final var executable = resolve(command.getFirst(), environment);
        try (final var arena = Arena.ofConfined()) {
            final var path = arena.allocateFrom(executable);
            final var argv = strings(arena, command);
            final var envp = strings(
                    arena,
                    environment.entrySet().stream()
                            .map(entry -> entry.getKey() + '=' + entry.getValue())
                            .toList());
            final var program = filter(arena, x86);
            final var state = arena.allocate(CALL_STATE);
            final var privilegesError =
                    arena.allocateFrom("Sandbox helper: PR_SET_NO_NEW_PRIVS failed, errno=0000000000\n");
            final var filterError = arena.allocateFrom("Sandbox helper: PR_SET_SECCOMP failed, errno=0000000000\n");
            final var execError = arena.allocateFrom("Sandbox helper: execve failed, errno=0000000000\n");
            final var foreignError = arena.allocateFrom("Sandbox helper: foreign call failed during seccomp/execve\n");
            // Everything is allocated and linked before restricting this OS thread. Do not
            // use TSYNC: sibling JVM threads disappear when execve replaces the process.
            try {
                if ((int) PRCTL_CALL.invokeExact(state, 38, 1L, 0L, 0L, 0L) != 0) {
                    reportErrno(privilegesError, state);
                } else if ((int) PRCTL_CALL.invokeExact(state, 22, 2L, program.address(), 0L, 0L) != 0) {
                    reportErrno(filterError, state);
                } else {
                    final int result = (int) EXECVE_CALL.invokeExact(state, path, argv, envp);
                    reportErrno(execError, state);
                }
            } catch (final Throwable failure) {
                // No exception formatting, Java I/O, allocation or unfiltered retry here.
                writeError(foreignError);
            } finally {
                // Never unwind through Java shutdown or leave an unfiltered sibling alive
                // if seccomp/execve failed. _exit is libc exit_group on Linux.
                EXIT_CALL.invokeExact(125);
            }
            throw new IllegalStateException("libc _exit unexpectedly returned");
        }
    }

    private static void reportErrno(final MemorySegment message, final MemorySegment state) throws Throwable {
        var errno = state.get(JAVA_INT, ERRNO_OFFSET);
        // Fixed-width decimal digits in a preallocated native buffer, before any other libc call.
        for (var offset = message.byteSize() - 3; offset >= message.byteSize() - 12; offset--) {
            message.set(JAVA_BYTE, offset, (byte) ('0' + errno % 10));
            errno /= 10;
        }
        writeError(message);
    }

    private static void writeError(final MemorySegment message) throws Throwable {
        // Best-effort diagnostic; even a failed/short write must lead directly to _exit.
        final long written = (long) WRITE_CALL.invokeExact(2, message, message.byteSize() - 1);
    }

    private static boolean requireArchitecture() {
        final var arch = System.getProperty("os.arch", "");
        if (!"Linux".equals(System.getProperty("os.name"))
                || ADDRESS.byteSize() != 8
                || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN
                || !("amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch))) {
            throw new UnsupportedOperationException(
                    "Sandbox helper requires Linux x86_64 or aarch64 (64-bit little endian)");
        }
        return !"aarch64".equals(arch);
    }

    private static String resolve(final String command, final Map<String, String> environment) {
        if (command.contains("/")) {
            return command;
        }
        for (final var directory :
                environment.getOrDefault("PATH", "/usr/bin:/bin").split(":", -1)) {
            final var candidate = Path.of(directory.isEmpty() ? "." : directory).resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalArgumentException("Sandbox executable not found: " + command);
    }

    private static MemorySegment strings(final Arena arena, final List<String> strings) {
        final var pointers = arena.allocate((strings.size() + 1L) * ADDRESS.byteSize(), ADDRESS.byteAlignment());
        for (var i = 0; i < strings.size(); i++) {
            if (strings.get(i).contains("\0")) {
                throw new IllegalArgumentException("NUL in execve argument or environment");
            }
            pointers.setAtIndex(ADDRESS, i, arena.allocateFrom(strings.get(i)));
        }
        pointers.setAtIndex(ADDRESS, strings.size(), MemorySegment.NULL);
        return pointers;
    }

    private static MemorySegment filter(final Arena arena, final boolean x86) {
        final var code = new ArrayList<int[]>();
        instruction(code, 0x20, 0, 0, 4); // seccomp_data.arch
        instruction(code, 0x15, 1, 0, x86 ? 0xc000003e : 0xc00000b7);
        instruction(code, 0x06, 0, 0, 0x80000000); // KILL_PROCESS: incompatible ABI
        instruction(code, 0x20, 0, 0, 0); // seccomp_data.nr
        if (x86) {
            instruction(code, 0x45, 0, 1, 0x40000000); // x32 ABI
            instruction(code, 0x06, 0, 0, 0x80000000);
        }
        // mount, umount2, pivot_root, chroot, unshare, setns,
        // ptrace, process_vm_readv/writev, bpf, perf_event_open, keyctl/add_key/request_key,
        // open_by_handle_at/name_to_handle_at, userfaultfd, kexec_load, module calls,
        // reboot, swapon/swapoff, fanotify_init, kexec_file_load.
        final var denied = x86
                ? new int[] {
                    165, 166, 155, 161, 272, 308, 101, 310, 311, 321, 298, 250, 248, 249, 304, 303, 323, 246, 175, 313,
                    176, 169, 167, 168, 300, 320
                }
                : new int[] {
                    40, 39, 41, 51, 97, 268, 117, 270, 271, 280, 241, 219, 217, 218, 265, 264, 282, 104, 105, 273, 106,
                    142, 224, 225, 262, 294
                };
        for (final var syscall : denied) {
            block(code, syscall, 1);
        }
        // Common modern syscall numbers: io_uring (all operations), new mount API,
        // pidfd_getfd, mount_setattr and open_tree_attr.
        for (final var syscall : new int[] {425, 426, 427, 428, 429, 430, 431, 432, 433, 438, 442, 467}) {
            block(code, syscall, 1);
        }
        block(code, 435, 38); // clone3 => ENOSYS so libc can fall back to clone
        instruction(code, 0x15, 0, 3, x86 ? 56 : 220); // clone
        instruction(code, 0x20, 0, 0, 16); // args[0], low word
        instruction(code, 0x45, 0, 1, 0x7e020000); // all CLONE_NEW* namespaces
        instruction(code, 0x06, 0, 0, 0x00050001); // ERRNO | EPERM
        instruction(code, 0x20, 0, 0, 0);
        // JDK channel initialization and subprocess plumbing require anonymous stream
        // pairs. These cannot connect to controller paths. Deny every other family/type,
        // especially Unix DGRAM/SEQPACKET; direct AF_UNIX socket creation stays forbidden.
        instruction(code, 0x15, 0, 8, x86 ? 53 : 199); // socketpair
        instruction(code, 0x20, 0, 0, 16); // family
        instruction(code, 0x15, 1, 0, 1); // AF_UNIX
        instruction(code, 0x06, 0, 0, 0x00050001);
        instruction(code, 0x20, 0, 0, 24); // type
        instruction(code, 0x54, 0, 0, 0xf); // ignore SOCK_CLOEXEC and SOCK_NONBLOCK
        instruction(code, 0x15, 1, 0, 1); // SOCK_STREAM only
        instruction(code, 0x06, 0, 0, 0x00050001);
        instruction(code, 0x06, 0, 0, 0x7fff0000);
        instruction(code, 0x15, 0, 4, x86 ? 41 : 198); // socket
        instruction(code, 0x20, 0, 0, 16);
        instruction(code, 0x15, 2, 0, 2); // AF_INET
        instruction(code, 0x15, 1, 0, 10); // AF_INET6
        instruction(code, 0x06, 0, 0, 0x00050001); // includes AF_UNIX
        instruction(code, 0x06, 0, 0, 0x7fff0000); // ALLOW
        final var instructions = arena.allocate(code.size() * 8L, 8);
        for (var i = 0; i < code.size(); i++) {
            final var op = code.get(i);
            final var offset = i * 8L;
            instructions.set(JAVA_SHORT, offset, (short) op[0]);
            instructions.set(JAVA_BYTE, offset + 2, (byte) op[1]);
            instructions.set(JAVA_BYTE, offset + 3, (byte) op[2]);
            instructions.set(JAVA_INT, offset + 4, op[3]);
        }
        final var program = arena.allocate(FPROG);
        program.set(JAVA_SHORT, 0, (short) code.size());
        program.set(ADDRESS, 8, instructions);
        return program;
    }

    private static void block(final List<int[]> code, final int syscall, final int errno) {
        instruction(code, 0x15, 0, 1, syscall);
        instruction(code, 0x06, 0, 0, 0x00050000 | errno);
    }

    private static void instruction(
            final List<int[]> code, final int op, final int yes, final int no, final int value) {
        code.add(new int[] {op, yes, no, value});
    }
}
