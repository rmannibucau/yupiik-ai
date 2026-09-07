package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import static io.yupiik.ia.agent.core.codegraph.GraphModel.Edge;
import static io.yupiik.ia.agent.core.codegraph.GraphModel.Symbol;

/**
 * Writes the agent-readable per-source-file markdown doc tree under the graph store, mirroring the
 * project source layout (src/main/java/.../Foo.java -> docs/src/main/java/.../Foo.java.md). Content is a
 * deterministic structural extract of the {@link GraphModel} (symbols and edges) so agents can grep the
 * docs dir instead of reading the graph. Optional {@code ## Summary} sections are filled asynchronously by
 * the {@link DocEnricher}.
 */
public class DocTreeWriter {
    private static final Logger LOGGER = Logger.getLogger(DocTreeWriter.class.getName());
    private static final String SUMMARY_MARK = "## Summary";

    private final CodingAgentConfiguration configuration;
    private final Path docsRoot;

    public DocTreeWriter(final CodingAgentConfiguration configuration, final Path docsRoot) {
        this.configuration = configuration;
        this.docsRoot = docsRoot;
    }

    public boolean enabled() {
        final var cfg = configuration.structuralCodeGraph();
        return cfg != null && cfg.docs() && docsRoot != null;
    }

    /**
     * Write (or refresh) the doc for a single parsed file, preserving an existing {@code ## Summary} section.
     * Returns the doc file path when emitted, null when skipped (disabled).
     */
    public Path write(final String relSource, final GraphModel model) {
        if (!enabled() || model == null) {
            return null;
        }
        final var docFile = docFile(relSource);
        if (docFile == null) {
            return null;
        }
        final var content = render(relSource, model);
        final StringBuilder body;
        try {
            body = readBody(docFile);
        } catch (final IOException e) {
            LOGGER.warning("Cannot read doc " + docFile + ": " + e.getMessage());
            return null;
        }
        if (body == null) {
            try {
                writeBody(docFile, content);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return docFile;
        }
        final var existing = body.toString();
        if (content.equals(previousStructuralBody(existing))) {
            return docFile;
        }
        final var summary = extract(existing, SUMMARY_MARK);
        final var rendered = summary == null ? content : content + "\n\n" + SUMMARY_MARK + "\n\n" + summary + '\n';
        try {
            writeBody(docFile, rendered);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return docFile;
    }

    /**
     * Inject (or replace) an LLM summary section into an existing doc. Called by the {@link DocEnricher}.
     * Returns the doc path, or null when nothing was written.
     */
    public Path writeSummary(final String relSource, final GraphModel model, final String summary) {
        if (!enabled() || model == null || summary == null) {
            return null;
        }
        final var docFile = docFile(relSource);
        if (docFile == null) {
            return null;
        }
        try {
            final StringBuilder body = readBody(docFile);
            final String current = body == null ? render(relSource, model) : body.toString();
            final var existing = extract(current, SUMMARY_MARK);
            final var section = SUMMARY_MARK + "\n\n" + summary + '\n';
            final String content = existing == null
                    ? current + (current.endsWith("\n") ? "" : "\n") + "\n" + section
                    : replaceSection(current, SUMMARY_MARK, summary);
            writeBody(docFile, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return docFile;
    }

    /**
     * Remove a source file's doc (used when the source file was deleted). True when a file was actually removed.
     */
    public boolean delete(final String relSource) {
        if (!enabled()) {
            return false;
        }
        final var docFile = docFile(relSource);
        if (docFile == null || !Files.isRegularFile(docFile)) {
            return false;
        }
        try {
            Files.delete(docFile);
            return true;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path docFile(final String relSource) {
        if (!enabled() || relSource == null || relSource.isBlank()) {
            return null;
        }
        final var fileName = relSource.substring(relSource.lastIndexOf('/') + 1);
        return docsRoot.resolve(relSource).resolveSibling(fileName + ".md");
    }

    /**
     * Deterministic structural markdown only (no summary section).
     */
    public String structuralDoc(final String relSource, final GraphModel model) {
        return render(relSource, model);
    }

    private static String previousStructuralBody(final String body) {
        final var idx = body.indexOf("\n" + SUMMARY_MARK);
        return idx >= 0 ? body.substring(0, idx) : body;
    }

    private String replaceSection(final String body, final String header, final String summary) {
        final var start = body.indexOf("\n" + header);
        if (start < 0) {
            return body + "\n" + header + "\n\n" + summary + '\n';
        }
        final var next = body.indexOf('\n', start + header.length());
        final var contentStart = next < 0 ? body.length() : next + 1;
        return body.substring(0, contentStart) + summary + '\n';
    }

    private String extract(final String body, final String header) {
        final var idx = body.indexOf(header);
        if (idx < 0) {
            return null;
        }
        final var lineStart = body.lastIndexOf('\n', idx);
        final var start = lineStart < 0 ? 0 : lineStart + 1;
        final var afterHeader = body.indexOf('\n', start);
        final var contentStart = afterHeader < 0 ? body.length() : afterHeader + 1;
        final var text = body.substring(contentStart).strip();
        return text.isEmpty() ? null : text;
    }

    private String render(final String relSource, final GraphModel model) {
        final var sb = new StringBuilder();
        final var types = new ArrayList<Symbol>();
        for (final var s : model.symbols()) {
            final var kind = s.kind();
            if (kind == GraphModel.Kind.TYPE || kind == GraphModel.Kind.INTERFACE || kind == GraphModel.Kind.ENUM
                    || kind == GraphModel.Kind.ANNOTATION || kind == GraphModel.Kind.MODULE) {
                types.add(s);
            }
        }
        if (types.isEmpty()) {
            sb.append("# ").append(relSource).append('\n');
        } else {
            types.sort(Comparator.comparing(Symbol::fqn));
            sb.append("# ").append(types.get(0).fqn()).append('\n');
        }
        sb.append("source: `").append(relSource).append("`\n");
        final var symbols = new ArrayList<>(model.symbols());
        symbols.sort(Comparator.comparing(Symbol::fqn).thenComparing(Symbol::kind));
        if (!symbols.isEmpty()) {
            sb.append("\n## Symbols\n");
            for (final var s : symbols) {
                sb.append("- `").append(s.fqn()).append("` ").append(s.kind().name().toLowerCase(Locale.ROOT));
                if (s.signature() != null && !s.signature().isBlank()) {
                    sb.append(" `").append(s.signature()).append('`');
                }
                sb.append(" @ ").append(s.line()).append('\n');
            }
        }
        final var edges = new ArrayList<>(model.edges());
        edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::type).thenComparing(Edge::to));
        if (!edges.isEmpty()) {
            sb.append("\n## Edges\n");
            for (final var e : edges) {
                sb.append("- `").append(e.from()).append('`').append(" --").append(e.type())
                        .append("--> `").append(e.to()).append("`\n");
            }
        }
        return sb.toString();
    }

    private StringBuilder readBody(final Path docFile) throws IOException {
        if (!Files.isRegularFile(docFile)) {
            return null;
        }
        return new StringBuilder(new String(Files.readAllBytes(docFile), StandardCharsets.UTF_8));
    }

    private void writeBody(final Path docFile, final String content) throws IOException {
        Files.createDirectories(docFile.getParent());
        Files.writeString(docFile, content, StandardCharsets.UTF_8);
    }
}