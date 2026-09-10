package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Optional;

/**
 * Daily cache of the context windows detected from the providers /v1/models endpoint.
 * Entries are keyed by provider id and model name, persisted as JSONL under the
 * {@code context-window} directory of the storage home and stay valid for a day.
 */
@ApplicationScoped
public class ContextWindowCache {
    private static final long TTL_MILLIS = 24L * 60L * 60L * 1000L;

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final Clock clock;

    public ContextWindowCache(final CodingAgentConfiguration configuration, final JsonMapper mapper, final Clock clock) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.clock = clock;
    }

    protected ContextWindowCache() {
        this.configuration = null;
        this.mapper = null;
        this.clock = null;
    }

    @JsonModel
    public record Entry(String providerId, String model, int window, Integer maxTokens, long fetchedAt) {
    }

    /** The cached window for the provider+model pair when fresher than a day; the last entry wins. */
    public Optional<Integer> get(final String providerId, final String model) {
        return getEntry(providerId, model).map(Entry::window);
    }

    /** The cached max output tokens for the provider+model pair when fresher than a day; nullable when absent or unknown. */
    public Optional<Integer> getMaxTokens(final String providerId, final String model) {
        return getEntry(providerId, model).flatMap(e -> e.maxTokens() == null ? Optional.empty() : Optional.of(e.maxTokens()));
    }

    private Optional<Entry> getEntry(final String providerId, final String model) {
        final var file = file();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        final var now = clock.millis();
        try {
            Optional<Entry> found = Optional.empty();
            for (final var line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                final Entry entry;
                try {
                    entry = mapper.fromString(Entry.class, line);
                } catch (final RuntimeException e) {
                    continue;
                }
                if (matches(entry, providerId, model)) {
                    found = now - entry.fetchedAt() <= TTL_MILLIS ? Optional.of(entry) : Optional.empty();
                }
            }
            return found;
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    public void put(final String providerId, final String model, final int window, final long fetchedAt) {
        put(providerId, model, window, null, fetchedAt);
    }

    public void put(final String providerId, final String model, final int window, final Integer maxTokens,
                    final long fetchedAt) {
        try {
            Files.createDirectories(dir());
            Files.writeString(file(), mapper.toString(new Entry(providerId, model, window, maxTokens, fetchedAt)) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean matches(final Entry entry, final String providerId, final String model) {
        return entry.providerId() != null && entry.model() != null
                && entry.providerId().equals(providerId) && entry.model().equals(model);
    }

    private Path dir() {
        return Path.of(configuration.resolveStorageHome(), "context-window");
    }

    private Path file() {
        return dir().resolve("windows.jsonl");
    }
}
