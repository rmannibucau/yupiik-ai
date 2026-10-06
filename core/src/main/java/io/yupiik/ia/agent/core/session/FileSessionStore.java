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
package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class FileSessionStore implements SessionStore {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ReentrantLock fileLock = new ReentrantLock();

    public FileSessionStore(final CodingAgentConfiguration configuration, final JsonMapper mapper, final Clock clock) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.clock = clock;
    }

    protected FileSessionStore() {
        this.configuration = null;
        this.mapper = null;
        this.clock = null;
    }

    public void onStart(@OnEvent final Start start) {
        cleanup();
        // root <storageHome>/todos-<key>.json and snapshots/<id>.snap whose session folder
        // is gone are stale legacy leftovers: drop them so the storage root stays clean
        SessionStorageLayout.sweepOrphans(Path.of(configuration.resolveStorageHome()));
    }

    public void cleanup() {
        housekeeping(false);
    }

    /**
     * Cleans the session directory: deletes sessions older than the retention
     * window, compresses sessions older than the compress window, and, when
     * {@code deleteEmpty}, removes sessions that hold nothing beyond the
     * initial prompt. Returns a report of what was done.
     */
    public HousekeepingReport housekeeping(final boolean deleteEmpty) {
        fileLock.lock();
        try {
            return doHousekeeping(deleteEmpty);
        } finally {
            fileLock.unlock();
        }
    }

    private HousekeepingReport doHousekeeping(final boolean deleteEmpty) {
        final var conf = configuration.sessions();
        if (conf == null) {
            return new HousekeepingReport(0, 0, 0);
        }
        final var now = now();
        final var retention = conf.retentionDays() > 0 ? conf.retentionDays() * MILLIS_PER_DAY : -1L;
        final var compress = conf.compressDays() > 0 ? conf.compressDays() * MILLIS_PER_DAY : -1L;
        var compressed = 0;
        var deleted = 0;
        var kept = 0;
        try (final Stream<Path> files = storedSessions()) {
            final var paths = files.toList();
            for (final var f : paths) {
                try {
                    final var name = f.getFileName().toString();
                    final var id = name.endsWith(JSONL_SUFFIX)
                            ? name.substring(0, name.length() - JSONL_SUFFIX.length())
                            : name.endsWith(GZ_SUFFIX) ? name.substring(0, name.length() - GZ_SUFFIX.length()) : name;
                    final var isFolder = Files.isDirectory(f);
                    final var folderJsonl = isFolder ? f.resolve(id + JSONL_SUFFIX) : null;
                    final var folderGz = isFolder ? f.resolve(id + GZ_SUFFIX) : null;
                    final Path target;
                    final boolean isCompressed;
                    if (isFolder) {
                        if (Files.isRegularFile(folderJsonl)) {
                            target = folderJsonl;
                            isCompressed = false;
                        } else if (Files.isRegularFile(folderGz)) {
                            target = folderGz;
                            isCompressed = true;
                        } else {
                            continue;
                        }
                    } else {
                        target = f;
                        isCompressed = name.endsWith(GZ_SUFFIX);
                    }
                    final var age = now - Files.getLastModifiedTime(target).toMillis();
                    if (retention >= 0 && age > retention) {
                        delete(id);
                        deleted++;
                    } else if (deleteEmpty && !isCompressed && isEmpty(id)) {
                        delete(id);
                        deleted++;
                    } else if (compress >= 0 && age > compress && !isCompressed) {
                        gzip(target);
                        compressed++;
                    } else {
                        kept++;
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new HousekeepingReport(compressed, deleted, kept);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot clean up the session directory " + dir(), e);
        }
    }

    private static void gzip(final Path jsonl) {
        final var gz = jsonl.resolveSibling(jsonl.getFileName().toString() + ".gz");
        try (final var in = new BufferedInputStream(Files.newInputStream(jsonl));
                final var out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(gz)))) {
            in.transferTo(out);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.deleteIfExists(jsonl);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path dir() {
        return Path.of(configuration.resolveStorageHome(), "sessions");
    }

    /** The folder owning all state of a session; may not exist yet. */
    public Path sessionDir(final String id) {
        return SessionStorageLayout.sessionDir(Path.of(configuration.resolveStorageHome()), id);
    }

    /**
     * Enumerates every stored session, in both layouts: a session folder
     * ({@code sessions/<id>/}) or a legacy flat file ({@code sessions/<id>.jsonl[.gz]}).
     */
    private Stream<Path> storedSessions() throws IOException {
        final var dir = dir();
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        return Files.list(dir).filter(f -> {
            final var name = f.getFileName().toString();
            if (Files.isDirectory(f)) {
                // only sessions folders: must contain the session JSONL (or its gz twin)
                return Files.isRegularFile(f.resolve(name + JSONL_SUFFIX))
                        || Files.isRegularFile(f.resolve(name + GZ_SUFFIX));
            }
            return name.endsWith(JSONL_SUFFIX) || name.endsWith(GZ_SUFFIX);
        });
    }

    private Path writableDir() {
        final var dir = dir();
        try {
            return Files.createDirectories(dir);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot create the session directory " + dir, e);
        }
    }

    @Override
    public String create() {
        writableDir();
        long stamp = clock.millis();
        var id = "s" + Long.toHexString(stamp);
        while (Files.exists(sessionDir(id)) || Files.exists(legacyPath(id)) || Files.exists(legacyGz(id))) {
            id = "s" + Long.toHexString(++stamp);
        }
        try {
            Files.createDirectories(path(id).getParent());
            Files.writeString(path(id), "");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return id;
    }

    public Path path(final String id) {
        return sessionDir(id).resolve(id + JSONL_SUFFIX);
    }

    /** The legacy flat file for a session ({@code sessions/<id>.jsonl}), if it still exists. */
    private Path legacyPath(final String id) {
        return dir().resolve(id + JSONL_SUFFIX);
    }

    /** The legacy flat gz for a session ({@code sessions/<id>.jsonl.gz}), if it still exists. */
    private Path legacyGz(final String id) {
        return dir().resolve(id + GZ_SUFFIX);
    }

    @Override
    public void append(final String id, final SessionEntry entry) {
        fileLock.lock();
        try {
            writableDir();
            // always write the session folder; a legacy flat file (if any) is left untouched
            final var target = path(id);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(
                        target, mapper.toString(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            fileLock.unlock();
        }
    }

    @Override
    public List<Message> load(final String id) {
        // resolve the source file: the session folder first (jsonl or gz), then the legacy flat
        // layout (jsonl or gz) as a read-only fallback - legacy files are never modified
        final var source = resolveLoadSource(id);
        if (source == null) {
            return List.of();
        }
        try (final var in = !source.gz()
                ? Files.newBufferedReader(source.path())
                : new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(source.path())), StandardCharsets.UTF_8))) {
            final var out = new ArrayList<Message>();
            // legacy sessions persisted the tool name as toolCallId: remember assistant call ids by
            // tool name so such entries can be remapped to the real call id on load
            final var allCallIds = new java.util.HashSet<String>();
            final var callIdsByTool = new java.util.HashMap<String, java.util.ArrayDeque<String>>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final SessionEntry e;
                try {
                    e = mapper.fromString(SessionEntry.class, line);
                } catch (final RuntimeException re) {
                    continue;
                }
                if (e.role() == null) {
                    continue;
                }
                if (e.toolCalls() != null) {
                    for (final var call : e.toolCalls()) {
                        if (call != null && call.id() != null && call.function() != null) {
                            allCallIds.add(call.id());
                            callIdsByTool
                                    .computeIfAbsent(call.function().name(), k -> new java.util.ArrayDeque<>())
                                    .add(call.id());
                        }
                    }
                }
                var toolCallId = e.toolCallId();
                if ("tool".equals(e.role()) && toolCallId != null && !allCallIds.contains(toolCallId)) {
                    // not a real call id: remap to the first unmatched call of that tool name
                    final var candidates = callIdsByTool.get(toolCallId);
                    if (candidates != null) {
                        toolCallId = candidates.poll();
                    }
                }
                out.add(new Message(e.role(), e.content(), e.name(), e.toolCalls(), toolCallId, e.others()));
            }
            return out;
        } catch (final IOException e) {
            return List.of();
        }
    }

    /** The file to read a session from, in priority order: folder jsonl, folder gz, legacy jsonl, legacy gz. */
    private LoadSource resolveLoadSource(final String id) {
        final var folder = sessionDir(id);
        final var folderJsonl = folder.resolve(id + JSONL_SUFFIX);
        if (Files.isRegularFile(folderJsonl)) {
            return new LoadSource(folderJsonl, false);
        }
        final var folderGz = folder.resolve(id + GZ_SUFFIX);
        if (Files.isRegularFile(folderGz)) {
            return new LoadSource(folderGz, true);
        }
        final var legacy = legacyPath(id);
        if (Files.isRegularFile(legacy)) {
            return new LoadSource(legacy, false);
        }
        final var legacyGz = legacyGz(id);
        if (Files.isRegularFile(legacyGz)) {
            return new LoadSource(legacyGz, true);
        }
        return null;
    }

    private record LoadSource(Path path, boolean gz) {}

    @Override
    public void replace(final String id, final List<Message> messages) {
        fileLock.lock();
        try {
            final var sb = new StringBuilder();
            for (final var m : messages) {
                sb.append(mapper.toString(SessionEntry.of(
                                now(), m.role(), m.content(), m.toolCalls(), m.toolCallId(), m.name(), m.others())))
                        .append('\n');
            }
            final var target = path(id);
            writableDir();
            // a legacy flat file (if any) is left untouched: the session folder is the write target
            final var tmp = target.resolveSibling(
                    target.getFileName() + ".tmp-" + ThreadLocalRandom.current().nextLong());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(tmp, sb.toString());
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (final IOException ignored) {
                    // best effort cleanup
                }
                throw new UncheckedIOException("Cannot replace the session " + id, e);
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * A session is considered empty when its persisted content holds nothing
     * more than the single initial user prompt (or nothing at all).
     */
    @Override
    public boolean isEmpty(final String id) {
        final var messages = load(id);
        if (messages.isEmpty()) {
            return true;
        }
        return messages.size() == 1 && "user".equals(messages.get(0).role());
    }

    @Override
    public void delete(final String id) {
        fileLock.lock();
        try {
            try {
                deleteRecursively(sessionDir(id));
                Files.deleteIfExists(legacyPath(id));
                Files.deleteIfExists(legacyGz(id));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            fileLock.unlock();
        }
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (final var children = Files.list(dir)) {
            for (final var child : children.toList()) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(dir);
    }

    @Override
    public String summary(final String id) {
        return load(id).stream()
                .filter(m -> "user".equals(m.role())
                        && m.content() instanceof final String text
                        && !text.isBlank()
                        && !text.startsWith("<compacted-context>")
                        && !text.startsWith("<pruned-context>"))
                .map(m -> (String) m.content())
                .map(AgentSessionService::firstLine)
                .findFirst()
                .orElse("");
    }

    public List<SessionInfo> list() {
        try (final Stream<Path> files = storedSessions()) {
            return files.map(f -> {
                        final var name = f.getFileName().toString();
                        final String id;
                        final Path stat;
                        if (Files.isDirectory(f)) {
                            id = name;
                            stat = Files.isRegularFile(f.resolve(name + JSONL_SUFFIX))
                                    ? f.resolve(name + JSONL_SUFFIX)
                                    : f.resolve(name + GZ_SUFFIX);
                        } else {
                            id = name.endsWith(JSONL_SUFFIX)
                                    ? name.substring(0, name.length() - JSONL_SUFFIX.length())
                                    : name.substring(0, name.length() - GZ_SUFFIX.length());
                            stat = f;
                        }
                        try {
                            return new SessionInfo(
                                    id, Files.getLastModifiedTime(stat).toMillis(), Files.size(stat));
                        } catch (final IOException e) {
                            return new SessionInfo(name, 0, 0);
                        }
                    })
                    .sorted(Comparator.comparingLong(SessionInfo::modifiedAt).reversed())
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
    }

    public String lastSessionId() {
        final var all = list();
        return all.isEmpty() ? null : all.get(0).id();
    }

    private static final String JSONL_SUFFIX = ".jsonl";
    private static final String GZ_SUFFIX = ".jsonl.gz";
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    public long now() {
        return clock.millis();
    }
}
