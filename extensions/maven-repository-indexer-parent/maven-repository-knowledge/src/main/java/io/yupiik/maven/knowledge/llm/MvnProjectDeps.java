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
package io.yupiik.maven.knowledge.llm;

import io.yupiik.maven.knowledge.Gav;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Resolves the effective project dependencies by running the build tool, sharing the exact same Maven parsing
 * logic between every {@link KnowledgeLlm} implementation (yca and the MCP server) so it does not drift apart.
 *
 * <p>{@code mvn dependency:list} reports one line per artifact as {@code g:a:type:version:scope}, optionally
 * followed by a trailing suffix (eg {@code  -- module ... [auto]}), so the matching regex is only anchored at
 * the start. The run does not specify an {@code includeScope} so every scope (provided, compile, test, runtime,
 * ...) is resolved; the returned coordinates are de-duplicated and ordered from the most to the least relevant
 * ({@link DependencyScope#order()} - provided first, then compile, then test) so bounded consumers handle the
 * important artifacts first.</p>
 */
public final class MvnProjectDeps {

    private static final Pattern COORDINATE = Pattern.compile("^([^:]+):([^:]+):([^:]+):([^:]+):([^:]+)");

    private MvnProjectDeps() {
        // no-op
    }

    /**
     * Resolves the project dependencies, caching the result per fingerprint (aggregate content hash) of the
     * project build files so {@code mvn} is only run when a build file actually changed.
     *
     * @param projectDir       the Maven project directory; when it is not an existing directory an empty list is
     *                         returned.
     * @param timeoutMs        how long to wait for {@code mvn} to complete before killing it.
     * @param localRepository  cache location; the resolution cache (keyed by the project fingerprint) is stored
     *                         there. When {@code null} no cache is used.
     * @return the project's resolved, de-duplicated dependency coordinates ordered by {@link DependencyScope#order()},
     *         empty when Maven is not runnable, times out, fails or the directory is absent.
     */
    public static List<Gav> resolve(final Path projectDir, final long timeoutMs, final Path localRepository) {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return List.of();
        }
        Path cacheFile = null;
        String fingerprint = null;
        if (localRepository != null) {
            fingerprint = fingerprint(projectDir);
            cacheFile = cacheFile(localRepository, fingerprint);
            final var cached = readCache(cacheFile);
            if (cached != null) {
                return cached;
            }
        }
        final var deps = resolveFromMaven(projectDir, timeoutMs);
        if (cacheFile != null && !deps.isEmpty()) {
            writeCache(cacheFile, deps);
        }
        return deps;
    }

    /**
     * {@code resolve} with no cache: always runs {@code mvn}.
     *
     * @param projectDir the Maven project directory.
     * @param timeoutMs  how long to wait for {@code mvn} to complete before killing it.
     * @return the resolved coordinates (same semantics as {@link #resolve(Path, long, Path)}).
     */
    public static List<Gav> resolve(final Path projectDir, final long timeoutMs) {
        return resolve(projectDir, timeoutMs, null);
    }

    private static List<Gav> resolveFromMaven(final Path projectDir, final long timeoutMs) {
        try {
            final var pb = new ProcessBuilder("mvn", "-o", "dependency:list");
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            final var process = pb.start();
            final String output;
            try (final var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            return parse(output);
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    // -- dependency-resolution caching (keyed by the project build files fingerprint) --

    /** Cache file layout: one coordinate ({@code groupId:artifactId:version}) per line. */
    private static Path cacheFile(final Path localRepository, final String fingerprint) {
        return localRepository.resolve(".yca").resolve("project-deps").resolve(fingerprint + ".deps");
    }

    private static List<Gav> readCache(final Path cacheFile) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            final var out = new java.util.ArrayList<Gav>();
            for (final var line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                final var stripped = line.strip();
                if (!stripped.isBlank()) {
                    try {
                        out.add(Gav.parse(stripped));
                    } catch (final IllegalArgumentException ignored) {
                        // ignore a corrupt line
                    }
                }
            }
            return out.isEmpty() ? null : List.copyOf(out);
        } catch (final IOException e) {
            return null;
        }
    }

    private static void writeCache(final Path cacheFile, final List<Gav> deps) {
        try {
            final var sb = new StringBuilder();
            for (final var gav : deps) {
                sb.append(gav.coordinate()).append('\n');
            }
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (final IOException ignored) {
            // a cache write failure is not fatal
        }
    }

    /** SHA-256 hex of the concatenated content of every project build file (poms + gradle + catalogs). */
    static String fingerprint(final Path projectDir) {
        final var md = MessageDigestHolder.digest();
        try (final var stream = Files.walk(projectDir)) {
            final var files = stream.filter(Files::isRegularFile)
                    .filter(path -> isBuildFile(path.getFileName().toString()))
                    .sorted()
                    .toList();
            for (final var file : files) {
                md.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(file));
                md.update((byte) 0);
            }
        } catch (final IOException e) {
            // fall through to the empty-document fingerprint
        }
        final var bytes = md.digest();
        final var sb = new StringBuilder(bytes.length * 2);
        for (final var b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isBuildFile(final String name) {
        return name.endsWith("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts")
                || name.equals("libs.versions.toml");
    }

    /** Small holder to avoid importing MessageDigest statics every call. */
    private static final class MessageDigestHolder {
        private static final ThreadLocal<java.security.MessageDigest> MD = ThreadLocal.withInitial(() -> {
            try {
                return java.security.MessageDigest.getInstance("SHA-256");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });

        private static java.security.MessageDigest digest() {
            final var md = MD.get();
            md.reset();
            return md;
        }
    }

    static List<Gav> sorted(final Map<Gav, DependencyScope> byCoordinate) {
        final var out = new java.util.ArrayList<>(byCoordinate.keySet());
        out.sort(Comparator.comparingInt(g -> byCoordinate.get(g).order()));
        return List.copyOf(out);
    }

    /** Parses {@code mvn dependency:list} output into de-duplicated coordinates ordered by scope priority. */
    static List<Gav> parse(final String output) {
        final Map<Gav, DependencyScope> byCoordinate = new LinkedHashMap<>();
        for (final var line : output.split("\\R")) {
            final var m = COORDINATE.matcher(stripLogPrefix(line));
            if (!m.find() || !"jar".equals(m.group(3))) {
                continue;
            }
            final var gav = Gav.of(m.group(1), m.group(2), m.group(4));
            // the scope can be followed by the trailing Maven suffix (eg " -- module ... [auto]")
            final var scope = DependencyScope.parse(m.group(5).split("\\s+", 2)[0]);
            // keep the most relevant scope when a coordinate is declared in several scopes
            byCoordinate.merge(gav, scope, (a, b) -> a.order() <= b.order() ? a : b);
        }
        return sorted(byCoordinate);
    }

    /** Strips the Maven log prefixes (eg {@code [INFO]    }) so the coordinate starts the line. */
    private static String stripLogPrefix(final String line) {
        return line.replaceAll("^(\\[[^]]+]\\s*)+", "").trim();
    }
}
