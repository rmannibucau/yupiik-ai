package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Stream;

@ApplicationScoped
public class SessionStore {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;

    public SessionStore(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    protected SessionStore() {
        this.configuration = null;
        this.mapper = null;
    }

    public void onStart(@OnEvent final Start start) {
        cleanup();
    }

    public void cleanup() {
        final var conf = configuration.sessions();
        if (conf == null) {
            return;
        }
        final var now = now();
        final var retention = conf.retentionDays() > 0 ? conf.retentionDays() * MILLIS_PER_DAY : -1L;
        final var compress = conf.compressDays() > 0 ? conf.compressDays() * MILLIS_PER_DAY : -1L;
        try (final Stream<Path> files = Files.list(dir())) {
            files.filter(f -> {
                final var name = f.getFileName().toString();
                return name.endsWith(JSONL_SUFFIX) || name.endsWith(GZ_SUFFIX);
            }).forEach(f -> {
                try {
                    final var age = now - Files.getLastModifiedTime(f).toMillis();
                    final var name = f.getFileName().toString();
                    if (retention >= 0 && age > retention) {
                        Files.deleteIfExists(f);
                    } else if (compress >= 0 && age > compress && !name.endsWith(GZ_SUFFIX)) {
                        gzip(f);
                    }
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
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

    private static void gunzip(final Path gz, final Path target) throws IOException {
        try (final var in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gz)));
             final var out = new BufferedOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
        Files.deleteIfExists(gz);
    }

    public Path dir() {
        return Path.of(configuration.resolveStorageHome(), "sessions");
    }

    public String create() {
        try {
            Files.createDirectories(dir());
            long stamp = System.currentTimeMillis();
            var id = "s" + Long.toHexString(stamp);
            while (Files.exists(path(id))) {
                id = "s" + Long.toHexString(++stamp);
            }
            Files.writeString(path(id), "");
            return id;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path path(final String id) {
        return dir().resolve(id + JSONL_SUFFIX);
    }

    public void append(final String id, final SessionEntry entry) {
        try {
            Files.createDirectories(dir());
            Files.writeString(path(id), mapper.toString(entry) + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Message> load(final String id) {
        var file = path(id);
        if (!Files.isRegularFile(file)) {
            final var gz = dir().resolve(id + GZ_SUFFIX);
            if (Files.isRegularFile(gz)) {
                try {
                    gunzip(gz, file);
                } catch (final IOException e) {
                    return List.of();
                }
            }
        }
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            final var out = new ArrayList<Message>();
            for (final var line : Files.readAllLines(file)) {
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
                out.add(new Message(e.role(), e.content(), e.name(), e.toolCalls(), e.toolCallId(), null));
            }
            return out;
        } catch (final IOException e) {
            return List.of();
        }
    }

    public void replace(final String id, final List<Message> messages) {
        final var sb = new StringBuilder();
        for (final var m : messages) {
            sb.append(mapper.toString(SessionEntry.of(now(), m.role(), m.content(), m.toolCalls(), m.toolCallId()))).append('\n');
        }
        try {
            Files.createDirectories(dir());
            Files.writeString(path(id), sb.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record SessionInfo(String id, long modifiedAt, long sizeBytes) {
    }

    public List<SessionInfo> list() {
        try (final Stream<Path> files = Files.list(dir())) {
            return files.filter(f -> {
                        final var name = f.getFileName().toString();
                        return name.endsWith(JSONL_SUFFIX) || name.endsWith(GZ_SUFFIX);
                    })
                    .map(f -> {
                        final var name = f.getFileName().toString();
                        final var id = name.endsWith(JSONL_SUFFIX)
                                ? name.substring(0, name.length() - JSONL_SUFFIX.length())
                                : name.substring(0, name.length() - GZ_SUFFIX.length());
                        try {
                            return new SessionInfo(id,
                                    Files.getLastModifiedTime(f).toMillis(), Files.size(f));
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

    public static long now() {
        return Instant.now().toEpochMilli();
    }
}
