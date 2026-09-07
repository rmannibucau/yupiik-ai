package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.optimize.AfterToolExecutionEvent;
import io.yupiik.ia.agent.core.optimize.BeforeToolExecutionEvent;
import io.yupiik.ia.agent.core.runtime.StatusReporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class StructuralCodeGraph {
    private static final Logger LOGGER = Logger.getLogger(StructuralCodeGraph.class.getName());
    private static final Set<String> SKIP_DIRS = Set.of("target", ".git", ".idea", ".yca", "node_modules", "build", "generated-sources");

    private final CodingAgentConfiguration configuration;
    private final List<GraphParser> parsers;
    private final JsonMapper mapper;
    private final StatusReporter statusReporter;
    private final ChatClient chatClient;
    private final Path storeRoot;
    private final Path storeFile;
    private volatile GraphStore graph;
    private volatile String pendingRefreshPath;

    public StructuralCodeGraph(final CodingAgentConfiguration configuration, final List<GraphParser> parsers,
                               final JsonMapper mapper, final StatusReporter statusReporter,
                               final ChatClient chatClient) {
        this.configuration = configuration;
        this.parsers = parsers.stream().filter(GraphParser::available).toList();
        this.mapper = mapper;
        this.statusReporter = statusReporter;
        this.chatClient = chatClient;
        final var workingDir = configuration.resolveWorkingDir().toString();
        this.storeRoot = Path.of(configuration.structuralCodeGraph().resolvedStoreDir(workingDir)).toAbsolutePath().normalize();
        this.storeFile = storeRoot.resolve("graph.json");
    }

    protected StructuralCodeGraph() {
        this.configuration = null;
        this.parsers = null;
        this.mapper = null;
        this.statusReporter = null;
        this.chatClient = null;
        this.storeRoot = null;
        this.storeFile = null;
    }

    public boolean enabled() {
        return configuration != null && configuration.structuralCodeGraph().enabled() && !parsers.isEmpty();
    }

    private boolean enrichEnabled() {
        return configuration != null && configuration.structuralCodeGraph().enabled()
                && configuration.structuralCodeGraph().enrich() && !parsers.isEmpty();
    }

    public Path storeRoot() {
        return storeRoot;
    }

    public void ensureReady() {
        if (!enabled() || graph != null) {
            return;
        }
        final var loaded = load();
        if (loaded != null) {
            final var patched = patch(loaded);
            graph = patched;
            return;
        }
        final var built = rebuild();
        graph = built;
    }

    public GraphStore graph() {
        if (!enabled()) {
            return GraphStore.empty();
        }
        ensureReady();
        final var g = graph;
        return g == null ? GraphStore.empty() : g;
    }

    public String freshness() {
        if (!enabled()) {
            return "disabled (enable yca.structural-code-graph.enabled)";
        }
        final var g = graph();
        int missing = 0;
        for (final var s : g.symbols()) {
            if (!Files.isRegularFile(Path.of(s.file()))) {
                missing++;
            }
        }
        return "symbols=" + g.symbols().size() + " edges=" + g.edges().size() + " missingFiles=" + missing
                + " files=" + g.fingerprints().size() + " storedAt=" + g.builtAt()
                + " dir=" + storeRoot;
    }

    public void onBeforeTool(@OnEvent final BeforeToolExecutionEvent event) {
        if (enabled() && isFileTool(event.tool())) {
            pendingRefreshPath = extractPath(event.args());
        }
    }

    public void onToolExecuted(@OnEvent final AfterToolExecutionEvent event) {
        if (!enabled() || !isFileTool(event.tool()) || !event.success()) {
            return;
        }
        final var path = pendingRefreshPath;
        pendingRefreshPath = null;
        if (path != null && !path.isBlank()) {
            refreshFile(Path.of(path));
        }
    }

    private boolean isFileTool(final String name) {
        return "write_file".equals(name) || "edit_file".equals(name);
    }

    private String extractPath(final Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        final var v = args.get("path");
        return v == null ? null : v.toString();
    }

    public void refreshFile(final Path file) {
        if (!enabled()) {
            return;
        }
        final var absolute = file.toAbsolutePath().normalize();
        final var rel = rel(absolute);
        final var parser = parserFor(absolute);
        if (parser == null) {
            return;
        }
        final var fp = fingerprint(absolute);
        final GraphModel model;
        try {
            model = fp == null ? GraphModel.empty(parser.extensions().iterator().next()) : parser.parse(absolute);
        } catch (final RuntimeException e) {
            LOGGER.warning("Cannot re-index " + rel + ": " + e.getMessage());
            return;
        }
        final var current = graph == null ? loadOrEmpty() : graph;
        final var symbols = new ArrayList<>(current.symbols());
        final var edges = new ArrayList<>(current.edges());
        dropFile(rel, symbols, edges);
        final var fingerprints = new HashMap<>(current.fingerprints());
        final var removed = fingerprints.remove(rel) != null;
        if (fp != null && (removed || !fp.equals(current.fingerprints().get(rel)))) {
            fingerprints.put(rel, fp);
            symbols.addAll(model.symbols());
            edges.addAll(model.edges());
        }
        final var updated = new GraphStore(List.copyOf(symbols), List.copyOf(edges), Map.copyOf(fingerprints), System.currentTimeMillis());
        graph = updated;
        persist(updated);
        statusReporter.report("structural code graph re-indexed " + rel);
    }

    private void dropFile(final String rel, final List<GraphModel.Symbol> symbols, final List<GraphModel.Edge> edges) {
        final var owned = new HashSet<String>();
        symbols.removeIf(s -> {
            if (s.file().equals(rel)) {
                owned.add(s.fqn());
                return true;
            }
            return false;
        });
        edges.removeIf(e -> owned.contains(e.from()));
    }

    private GraphStore load() {
        if (!Files.isRegularFile(storeFile)) {
            return null;
        }
        try {
            final var loaded = mapper.fromString(GraphStore.class, Files.readString(storeFile, StandardCharsets.UTF_8));
            return loaded == null ? null : loaded;
        } catch (final IOException | RuntimeException e) {
            LOGGER.warning("Cannot load structural code graph from " + storeFile + ": " + e.getMessage());
            return null;
        }
    }

    private GraphStore loadOrEmpty() {
        final var loaded = load();
        return loaded == null ? GraphStore.empty() : loaded;
    }

    private GraphStore patch(final GraphStore current) {
        final var fingerprints = new HashMap<>(current.fingerprints());
        final var symbols = new ArrayList<>(current.symbols());
        final var edges = new ArrayList<>(current.edges());
        var changed = false;
        for (final Path file : candidateFiles()) {
            final var rel = rel(file);
            final var fp = fingerprint(file);
            if (fp == null) {
                changed |= fingerprints.remove(rel) != null;
                continue;
            }
            if (fp.equals(fingerprints.get(rel))) {
                continue;
            }
            changed = true;
            fingerprints.put(rel, fp);
            dropFile(rel, symbols, edges);
            final var parser = parserFor(file);
            if (parser == null) {
                continue;
            }
            try {
                final var model = parser.parse(file);
                symbols.addAll(model.symbols());
                edges.addAll(model.edges());
            } catch (final RuntimeException e) {
                LOGGER.warning("Cannot re-index " + rel + ": " + e.getMessage());
            }
        }
        if (!changed) {
            return current;
        }
        final var updated = new GraphStore(List.copyOf(symbols), List.copyOf(edges), Map.copyOf(fingerprints), System.currentTimeMillis());
        persist(updated);
        statusReporter.report("structural code graph refreshed (" + updated.symbols().size() + " symbols)");
        return updated;
    }

    private GraphStore rebuild() {
        final var symbols = new ArrayList<GraphModel.Symbol>();
        final var edges = new ArrayList<GraphModel.Edge>();
        final var fingerprints = new HashMap<String, String>();
        var count = 0;
        for (final Path file : candidateFiles()) {
            final var rel = rel(file);
            final var fp = fingerprint(file);
            if (fp == null) {
                continue;
            }
            fingerprints.put(rel, fp);
            final var parser = parserFor(file);
            if (parser == null) {
                continue;
            }
            try {
                final var model = parser.parse(file);
                symbols.addAll(model.symbols());
                edges.addAll(model.edges());
                count++;
            } catch (final RuntimeException e) {
                LOGGER.warning("Cannot parse " + rel + ": " + e.getMessage());
            }
        }
        final var built = new GraphStore(List.copyOf(symbols), List.copyOf(edges), Map.copyOf(fingerprints), System.currentTimeMillis());
        persist(built);
        maybeEnrich(built);
        statusReporter.report("structural code graph built (" + built.symbols().size() + " symbols, " + count + " files)");
        return built;
    }

    private void maybeEnrich(final GraphStore store) {
        if (enrichEnabled() && (store.nodeFiles() == null || store.nodeFiles().isEmpty())) {
            new ConceptNodeBuilder(configuration, chatClient).run(storeRoot, store.symbols());
        }
    }

    private GraphParser parserFor(final Path file) {
        final var ext = extension(file);
        if (ext == null || !configuration.structuralCodeGraph().languageExtensions().contains(ext)) {
            return null;
        }
        for (final var parser : parsers) {
            if (parser.extensions().contains(ext)) {
                return parser;
            }
        }
        return null;
    }

    private List<Path> candidateFiles() {
        final Path root;
        try {
            root = configuration.resolveWorkingDir();
        } catch (final RuntimeException e) {
            return List.of();
        }
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        final var result = new ArrayList<Path>();
        try (final Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p))
                    .filter(this::sizeOk)
                    .forEach(result::add);
        } catch (final IOException e) {
            return List.of();
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    private boolean sizeOk(final Path p) {
        try {
            return Files.size(p) <= configuration.structuralCodeGraph().maxFileBytes();
        } catch (final IOException e) {
            return false;
        }
    }

    private boolean isSkipped(final Path p) {
        final var n = p.toString();
        return SKIP_DIRS.stream().anyMatch(n::contains);
    }

    private String extension(final Path p) {
        final var name = p.getFileName().toString();
        final var idx = name.lastIndexOf('.');
        return idx <= 0 || idx == name.length() - 1 ? null : name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    public String rel(final Path file) {
        try {
            return configuration.resolveWorkingDir().relativize(file).toString();
        } catch (final RuntimeException e) {
            return file.toString();
        }
    }

    private String fingerprint(final Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            try (final var in = Files.newInputStream(file)) {
                final var buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            final var bytes = digest.digest();
            final var sb = new StringBuilder(bytes.length * 2);
            for (final var b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (final IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private void persist(final GraphStore store) {
        try {
            Files.createDirectories(storeRoot);
            Files.writeString(storeFile, mapper.toString(store), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}