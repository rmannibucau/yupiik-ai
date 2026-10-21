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
package io.yupiik.ia.agent.core.codegraph.spawn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Locates a usable {@code java} launcher (a full JDK exposing a compiler) that a native image can spawn to run the
 * javac-backed parse. On a JVM there is already an in-process compiler so this is never used; in a native image there
 * is none, so we look one up eagerly (cached) before deciding whether the spawned parser is {@link
 * SpawnedJvmGraphParser#available}.
 *
 * <p>Search order: the {@code JAVA_HOME}/bin/java launcher, then every {@code java} on the {@code PATH}. Each candidate
 * is accepted only when a minimal probe process actually reports that {@code ToolProvider.getSystemJavaCompiler()} is
 * non-null (a plain JRE exposes none and must be skipped, not trusted). Caching is single-flight: the expensive probe
 * runs at most once per resolve.</p>
 */
public class JdkLocator {

    private volatile Path resolved;
    private final ReentrantLock lock = new ReentrantLock();

    /** Returns the first usable {@code java} launcher path, or {@code null} when none has a compiler. */
    public Path locate() {
        Path current = resolved;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (resolved != null) {
                return resolved;
            }
            final var candidates = candidates();
            Path found = null;
            for (final var candidate : candidates) {
                if (hasCompiler(candidate)) {
                    found = candidate;
                    break;
                }
            }
            resolved = found;
            return found;
        } finally {
            lock.unlock();
        }
    }

    /** Deterministic candidate order: JAVA_HOME first, then PATH in declaration order, de-duplicated. */
    List<Path> candidates() {
        final var out = new ArrayList<Path>();
        final var seen = new LinkedHashSet<Path>();
        final var javaHome = env("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            final var p = Path.of(javaHome, "bin", "java");
            if (Files.isExecutable(p) && seen.add(p)) {
                out.add(p);
            }
        }
        final var path = env("PATH");
        if (path != null) {
            for (final var dir : path.split(java.io.File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                final var p = Path.of(dir, "java");
                if (Files.isExecutable(p) && seen.add(p)) {
                    out.add(p);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Environment seam, overridable in tests to avoid depending on the machine's environment. */
    String env(final String name) {
        return System.getenv(name);
    }

    /** Runs a minimal probe in {@code java} to confirm a system compiler is present. */
    boolean hasCompiler(final Path java) {
        Path probeFile = null;
        try {
            probeFile = Files.createTempFile("yca-jdkprobe-", ".java");
            Files.writeString(
                    probeFile,
                    "class YcaJdkProbe { public static void main(String[] a) { "
                            + "System.out.print(javax.tools.ToolProvider.getSystemJavaCompiler() == null ? -1 : 1); } }");
            // --source runs the single-file launcher so no output dir or compile stage is needed. Not "--release":
            // that flag is only understood by javac/--compile, not by the `java file.java` source launcher. Every JDK
            // with a compiler supports source level 11, so fall back from 21 in case an older JAVA_HOME rejects it.
            final Object[] result = runProbe(java, "21", probeFile);
            if (Boolean.FALSE.equals(result[1]) && "21".equals(result[0])) {
                return Boolean.TRUE.equals(runProbe(java, "11", probeFile)[1]);
            }
            return Boolean.TRUE.equals(result[1]);
        } catch (final IOException e) {
            return false;
        } finally {
            try {
                if (probeFile != null) {
                    Files.deleteIfExists(probeFile);
                }
            } catch (final IOException ignored) {
                // best effort
            }
        }
    }

    /** Runs the probe at the given source level; returns {@code [level, acceptedAndCompilerPresent]}. */
    private Object[] runProbe(final Path java, final String sourceLevel, final Path probeFile) throws IOException {
        try {
            final var process = new ProcessBuilder(
                            java.toAbsolutePath().toString(),
                            "--source",
                            sourceLevel,
                            probeFile.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Object[] {sourceLevel, Boolean.FALSE};
            }
            final var out = readFully(process.getInputStream());
            return new Object[] {
                sourceLevel,
                Boolean.valueOf(process.exitValue() == 0 && out.trim().contains("1"))
            };
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Object[] {sourceLevel, Boolean.FALSE};
        }
    }

    private static String readFully(final InputStream in) throws IOException {
        final var buf = new ByteArrayOutputStream();
        final var chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
