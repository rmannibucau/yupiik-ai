package io.yupiik.ia.agent.core.memory;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class MemoryStore {
    public static final String HEADER = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n";

    private final CodingAgentConfiguration configuration;

    public MemoryStore(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected MemoryStore() {
        this.configuration = null;
    }

    public Path globalFile() {
        return Path.of(configuration.resolveStorageHome(), "memory", "MEMORY.md");
    }

    public Path projectFile() {
        return Path.of(".", ".yca", "memory", "MEMORY.md");
    }

    public String readGlobal() {
        return read(globalFile());
    }

    public String readProject() {
        return read(projectFile());
    }

    public String promptSection() {
        final var max = Math.max(500, configuration.memory().maxChars());
        final var global = readGlobal();
        final var project = readProject();
        if (global.isBlank() && project.isBlank()) {
            return "";
        }
        final var sb = new StringBuilder("<memory>\n");
        if (!global.isBlank()) {
            sb.append("<global>\n").append(global).append("\n</global>\n");
        }
        if (!project.isBlank()) {
            sb.append("<project>\n").append(project).append("\n</project>\n");
        }
        sb.append("</memory>");
        final var s = sb.toString();
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    public synchronized List<String> append(final boolean global, final List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        final var file = global ? globalFile() : projectFile();
        try {
            Files.createDirectories(file.getParent());
            final var existing = new LinkedHashSet<String>(existingLines(file));
            final var added = new ArrayList<String>();
            for (final var fact : facts) {
                final var line = fact.strip();
                if (line.isEmpty() || line.length() > 500) {
                    continue;
                }
                final var normalized = line.startsWith("-") ? line.strip() : "- " + line;
                if (existing.add(normalized)) {
                    added.add(normalized);
                }
            }
            if (!added.isEmpty()) {
                final var content = new StringBuilder(HEADER);
                existing.forEach(l -> content.append(l).append('\n'));
                Files.writeString(file, content.toString());
            }
            return added;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> existingLines(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
    }

    private String read(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            final var content = Files.readString(file);
            final var max = Math.max(500, configuration.memory().maxChars());
            return content.length() > max ? content.substring(content.length() - max) : content;
        } catch (final IOException e) {
            return "";
        }
    }
}
