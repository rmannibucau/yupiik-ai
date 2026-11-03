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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    /** Id of the session created first for this process (never swept by housekeeping). */
    private volatile String primarySessionId;

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
        // remember the most recent stored session so startup housekeeping can never delete
        // the session the user is about to resume (it exists before the frontends resolve it)
        primarySessionId = configuration.sessions() == null ? null : lastSessionId();
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

    /**
     * Id the process must never discard during housekeeping: the session resolved for
     * this process (created or resumed). Callers resolving their session should register
     * it via {@link #keepSession(String)} so a concurrent housekeeping run - and, for
     * file-backed stores, a restart - never deletes it.
     */
    public void keepSession(final String id) {
        if (id != null && !id.isBlank()) {
            primarySessionId = id;
        }
    }

    private boolean isProtectedFromHousekeeping(final String id) {
        // the session resolved for this process, and everything written within the
        // recent grace window (a long-lived active session must never be dropped on
        // age alone - retention is about abandoned sessions)
        return id != null && (id.equals(primarySessionId) || isRecent(id));
    }

    private boolean isRecent(final String id) {
        return now() - lastModified(id) < DEFAULT_HOUSEKEEPING_GRACE_MILLIS;
    }

    private long lastModified(final String id) {
        final var target = path(id);
        try {
            if (Files.isRegularFile(target)) {
                return Files.getLastModifiedTime(target).toMillis();
            }
            final var gz = Path.of(target + GZ_SUFFIX);
            if (Files.isRegularFile(gz)) {
                return Files.getLastModifiedTime(gz).toMillis();
            }
            final var legacy = legacyPath(id);
            return Files.isRegularFile(legacy)
                    ? Files.getLastModifiedTime(legacy).toMillis()
                    : 0L;
        } catch (final IOException e) {
            return 0L;
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
                    if (isProtectedFromHousekeeping(id) && !(deleteEmpty && !isCompressed && isEmpty(id))) {
                        kept++;
                        continue;
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
            throw new UncheckedIOException("Cannot clean up the session directory '" + dir() + "'", e);
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
            // the gz twin supersedes the pre-compression backup: no rollback target remains
            Files.deleteIfExists(jsonl.resolveSibling(jsonl.getFileName().toString() + ".bak"));
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
            throw new UncheckedIOException("Cannot create the session directory '" + dir + "'", e);
        }
    }

    @Override
    public String create() {
        fileLock.lock();
        try {
            return doCreate();
        } finally {
            fileLock.unlock();
        }
    }

    private String doCreate() {
        writableDir();
        // Session ids are s + creation-time(seconds, hex) + random(hex) and must stay <= 16 chars so they
        // remain UI-friendly to display and paste. We deliberately use second (not millisecond) granularity
        // to keep the prefix short and the id within that budget: within a single second the 7 random hex
        // chars (28 bits) still make ids collision-free without scanning the store, and the coarser timestamp
        // only means two sessions created in the same wall-clock second are not strictly ordered - which is
        // functionally acceptable for a local session id.
        final var now = clock.instant(); // injectable, deterministic in tests
        final var seconds = now.getEpochSecond(); // up to 8 hex chars for ~7000 years
        final var random = ThreadLocalRandom.current().nextInt() & 0x0FFFFFFF; // 28 bits => 7 hex chars
        final String id = "s" + Long.toHexString(seconds) + String.format("%07x", random);
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
        try (final var in = source.reader()) {
            final var lines = new ArrayList<String>();
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return mapLines(lines);
        } catch (final IOException e) {
            return List.of();
        }
    }

    @Override
    public List<Message> tail(final String id, final int count) {
        if (count <= 0) {
            return List.of();
        }
        final var source = resolveLoadSource(id);
        if (source == null) {
            return List.of();
        }
        try {
            final var lines = new ArrayList<String>();
            if (!source.gz()) {
                // reverse line scan on the plain file: read only the last `count` non-blank lines
                final var path = source.path();
                final var size = Files.size(path);
                final var raf = new java.io.RandomAccessFile(path.toFile(), "r");
                try {
                    long pos = size;
                    var pending = new StringBuilder();
                    while (pos > 0 && lines.size() < count) {
                        final var chunk = Math.min(64 * 1024, pos);
                        pos -= chunk;
                        final var bytes = new byte[(int) chunk];
                        raf.seek(pos);
                        raf.readFully(bytes);
                        // prepend the chunk to the pending buffer (we scan backwards)
                        pending.insert(0, new String(bytes, StandardCharsets.UTF_8));
                        final var text = pending.toString();
                        pending.setLength(0);
                        // split the buffer: complete lines are between the last '\n' and the end
                        final var lastNl = text.lastIndexOf('\n');
                        if (lastNl >= 0) {
                            // tail of this chunk (after the last \n) is a complete line
                            final var tailPart = text.substring(lastNl + 1);
                            if (!tailPart.isBlank()) {
                                lines.add(0, tailPart);
                            }
                            // the head (before the last \n) may hold more complete lines
                            final var head = text.substring(0, lastNl);
                            if (!head.isEmpty()) {
                                pending.insert(0, head);
                            }
                        } else {
                            // no newline: the whole chunk is partial, keep it pending
                            pending.insert(0, text);
                        }
                    }
                    // handle the very first pending buffer (file start, no leading newline)
                    if (lines.size() < count && pending.length() > 0) {
                        final var tailPart = pending.toString();
                        if (!tailPart.isBlank()) {
                            lines.add(0, tailPart);
                        }
                    }
                } finally {
                    raf.close();
                }
            } else {
                // gz: fall back to a full read and take the last lines (compressed sessions are
                // usually compacted/old, so reading them fully is acceptable)
                try (final var in = source.reader()) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (!line.isBlank()) {
                            lines.add(line);
                            if (lines.size() > count) {
                                lines.remove(0);
                            }
                        }
                    }
                }
            }
            return mapLines(lines);
        } catch (final IOException e) {
            return List.of();
        }
    }

    @Override
    public int count(final String id) {
        final var source = resolveLoadSource(id);
        if (source == null) {
            return 0;
        }
        try {
            if (!source.gz()) {
                // cheap line count: scan the file counting '\n' (JSONL lines; a trailing missing
                // newline still counts as one line when the file is non-empty)
                final var size = Files.size(source.path());
                if (size == 0) {
                    return 0;
                }
                try (final var in = Files.newInputStream(source.path())) {
                    var count = 0;
                    final var buffer = new byte[64 * 1024];
                    int read;
                    var last = (byte) -1;
                    while ((read = in.read(buffer)) > 0) {
                        for (var i = 0; i < read; i++) {
                            if (buffer[i] == '\n') {
                                count++;
                            }
                            last = buffer[i];
                        }
                    }
                    return last == '\n' ? count : count + 1;
                }
            }
            try (final var in = source.reader()) {
                var count = 0;
                while (in.readLine() != null) {
                    count++;
                }
                return count;
            }
        } catch (final IOException e) {
            return 0;
        }
    }

    /**
     * Parses persisted JSONL lines into {@link Message}s with the same legacy
     * {@code toolCallId -> toolName} remap as {@link #load(String)}. Shared by the
     * full read and the tail read so both produce identical display shapes.
     */
    private List<Message> mapLines(final List<String> lines) {
        final var out = new ArrayList<Message>(lines.size());
        final var allCallIds = new HashSet<String>();
        final var callIdsByTool = new HashMap<String, ArrayDeque<String>>();
        for (final var line : lines) {
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
                                .computeIfAbsent(call.function().name(), k -> new ArrayDeque<>())
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
            // preserve the persisted timestamp inside others (reserved key), so a later
            // replace() can round-trip it instead of re-stamping history with now()
            final var others = e.others() == null ? null : new java.util.LinkedHashMap<>(e.others());
            if (others != null) {
                others.put(PERSISTED_AT_KEY, e.at());
            }
            out.add(new Message(e.role(), e.content(), e.name(), e.toolCalls(), toolCallId, others));
        }
        return out;
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

    private record LoadSource(Path path, boolean gz) {
        BufferedReader reader() throws IOException {
            return !gz()
                    ? Files.newBufferedReader(path)
                    : new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void replace(final String id, final List<Message> messages) {
        fileLock.lock();
        try {
            // safety net: never overwrite a session that holds content with an empty history -
            // a transient read error must not be able to wipe a good file (load-then-replace
            // call sites all pass through here)
            if (messages.isEmpty() && hasStoredContent(id)) {
                throw new UncheckedIOException(new IOException(
                        "Refusing to replace session '" + id + "' with an empty history: the stored session "
                                + "has content and the in-memory copy may be incomplete"));
            }
            final var sb = new StringBuilder();
            for (final var m : messages) {
                // persist the original entry timestamp when known: the message carries its
                // persisted "at" in others so a flush/compact/prune does not rewrite history
                final var at = m.others() == null ? null : m.others().get(PERSISTED_AT_KEY);
                final var stamp = at instanceof final Number n ? n.longValue() : now();
                final var others = m.others() == null ? null : new java.util.LinkedHashMap<>(m.others());
                if (others != null) {
                    others.remove(PERSISTED_AT_KEY);
                }
                sb.append(mapper.toString(SessionEntry.of(
                                stamp, m.role(), m.content(), m.toolCalls(), m.toolCallId(), m.name(), others)))
                        .append('\n');
            }
            final var target = path(id);
            writableDir();
            // a legacy flat file (if any) is left untouched: the session folder is the write target
            final var tmp = target.resolveSibling(
                    target.getFileName() + ".tmp-" + ThreadLocalRandom.current().nextLong());
            try {
                Files.createDirectories(target.getParent());
                // keep a .bak sibling so a bad rewrite can be restored by hand
                if (Files.isRegularFile(target)) {
                    Files.copy(
                            target,
                            target.resolveSibling(target.getFileName() + ".bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(tmp, sb.toString());
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (final IOException ignored) {
                    // best effort cleanup
                }
                throw new UncheckedIOException("Cannot replace the session '" + id + "'", e);
            }
        } finally {
            fileLock.unlock();
        }
    }

    private boolean hasStoredContent(final String id) {
        final var folder = path(id);
        try {
            if (Files.isRegularFile(folder) && Files.size(folder) > 0) {
                return true;
            }
            final var folderGz = Path.of(folder + GZ_SUFFIX);
            if (Files.isRegularFile(folderGz) && Files.size(folderGz) > 0) {
                return true;
            }
            return (Files.isRegularFile(legacyPath(id)) && Files.size(legacyPath(id)) > 0)
                    || (Files.isRegularFile(legacyGz(id)) && Files.size(legacyGz(id)) > 0);
        } catch (final IOException e) {
            return true; // cannot prove the session is writable: do not overwrite
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
        // leading system markers (the web project marker, the compaction marker) are structural
        // and do not make a session "non-empty"; only a real user turn does.
        var first = 0;
        while (first < messages.size() && "system".equals(messages.get(first).role())) {
            first++;
        }
        return first >= messages.size()
                || messages.size() - first == 1
                        && "user".equals(messages.get(first).role());
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
    public String projectDirOf(final String id) {
        try (final var in = openReader(id)) {
            if (in == null) {
                return "";
            }
            final var first = in.readLine();
            if (first == null || first.isBlank()) {
                return "";
            }
            final SessionEntry entry;
            try {
                entry = mapper.fromString(SessionEntry.class, first);
            } catch (final RuntimeException re) {
                return "";
            }
            final var content = entry.content();
            return "system".equals(entry.role()) && "project".equals(entry.name()) && content instanceof final String s
                    ? s
                    : "";
        } catch (final IOException e) {
            return "";
        }
    }

    @Override
    public String summary(final String id) {
        // the home-page listing calls summary once per session; loading and parsing the whole file
        // for each of them is O(total bytes). The title is the first line of the first real user
        // message, which sits near the top (after the leading system markers), so stream the JSONL
        // until we find it and bail out - same result as load().stream() but bounded by the prefix.
        try (final var in = openReader(id)) {
            if (in == null) {
                return "";
            }
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final SessionEntry entry;
                try {
                    entry = mapper.fromString(SessionEntry.class, line);
                } catch (final RuntimeException re) {
                    continue;
                }
                if (!"user".equals(entry.role())
                        || !(entry.content() instanceof final String text)
                        || text.isBlank()
                        || text.startsWith("<compacted-context>")
                        || text.startsWith("<pruned-context>")) {
                    continue;
                }
                return AgentSessionService.firstLine(text);
            }
            return "";
        } catch (final IOException e) {
            return "";
        }
    }

    /**
     * Opens a reader over the session's persisted content ({@code null} when no source exists),
     * resolving the same folder/legacy, jsonl/gz priority as {@link #load}: folder jsonl, folder
     * gz, legacy jsonl, legacy gz. Callers are responsible for closing it (try-with-resources).
     */
    private BufferedReader openReader(final String id) throws IOException {
        final var source = resolveLoadSource(id);
        if (source == null) {
            return null;
        }
        return !source.gz()
                ? Files.newBufferedReader(source.path())
                : new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(source.path())), StandardCharsets.UTF_8));
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
                            return new SessionInfo(id, 0, 0);
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
    /** Reserved {@code others} key carrying the persisted entry timestamp for replace() round-trip. */
    static final String PERSISTED_AT_KEY = "at";

    static final long DEFAULT_HOUSEKEEPING_GRACE_MILLIS = 24L * 60L * 60L * 1000L;

    public long now() {
        return clock.millis();
    }
}
