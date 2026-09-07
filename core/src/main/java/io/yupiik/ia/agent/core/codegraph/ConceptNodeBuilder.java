package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.StructuralCodeGraphConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.yupiik.ia.agent.core.codegraph.GraphModel.Symbol;

/**
 * Optional pass that groups structural symbols into concept clusters (subsystems by package/prefix)
 * and asks the LLM to produce a short markdown "concept node" summary per cluster. Results are cached
 * per cluster content-hash under the graph store, so unchanged subsystems are not re-priced.
 */
public class ConceptNodeBuilder {
    private static final Logger LOGGER = Logger.getLogger(ConceptNodeBuilder.class.getName());

    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;

    public ConceptNodeBuilder(final CodingAgentConfiguration configuration, final ChatClient chatClient) {
        this.configuration = configuration;
        this.chatClient = chatClient;
    }

    public void run(final Path storeRoot, final List<Symbol> symbols) {
        final StructuralCodeGraphConfiguration cfg = configuration.structuralCodeGraph();
        if (cfg == null || !cfg.enabled() || !cfg.enrich()) {
            return;
        }
        final Path nodes = storeRoot.resolve("nodes");
        final var clusters = cluster(symbols);
        try {
            Files.createDirectories(nodes);
            for (final var entry : clusters.entrySet()) {
                final var clusterFile = nodes.resolve(sanitize(entry.getKey()) + ".md");
                final var contentHash = hash(entry.getValue().toString());
                if (Files.isRegularFile(clusterFile) && Files.exists(clusterFile)) {
                    final var existing = new String(Files.readAllBytes(clusterFile), StandardCharsets.UTF_8);
                    if (existing.startsWith("<!-- hash=" + contentHash + " -->")) {
                        continue;
                    }
                }
                try {
                    final var summary = summarize(entry.getKey(), entry.getValue());
                    Files.writeString(clusterFile,
                            "<!-- hash=" + contentHash + " -->\n# " + entry.getKey() + "\n\n" + summary + "\n",
                            StandardCharsets.UTF_8);
                } catch (final IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LOGGER.warning("Cannot enrich " + entry.getKey() + ": " + e.getMessage());
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String summarize(final String cluster, final List<Symbol> symbols)
            throws IOException, InterruptedException {
        final var prompt = new StringBuilder();
        prompt.append("You are summarizing a code subsystem for a coding agent.\n");
        prompt.append("Subsystem: ").append(cluster).append('\n');
        prompt.append("Symbols:\n");
        for (final var s : symbols) {
            prompt.append("- ").append(s.fqn()).append(" (").append(s.kind()).append(")");
            if (s.signature() != null && !s.signature().isBlank()) {
                prompt.append(" ").append(s.signature());
            }
            prompt.append('\n');
        }
        prompt.append("Answer in a concise markdown node of max 12 lines: purpose, key types, and dependencies.\n");
        final var request = new ChatCompletionRequest(resolveModel(), List.of(Message.user(prompt.toString())),
                (Double) null, false, null, null, 800, null, null);
        final var result = chatClient.chat(request);
        return text(result.message().content());
    }

    private String resolveModel() {
        final var override = configuration.structuralCodeGraph().model();
        return override == null || override.isBlank()
                ? configuration.provider().model()
                : override;
    }

    private String text(final Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            final var sb = new StringBuilder();
            for (final var p : parts) {
                if (p instanceof Map<?, ?> m && m.get("text") != null) {
                    sb.append(m.get("text"));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }

    private Map<String, List<Symbol>> cluster(final List<Symbol> symbols) {
        final var out = new LinkedHashMap<String, List<Symbol>>();
        for (final var s : symbols) {
            final var key = keyOf(s);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        out.values().forEach(v -> v.sort(Comparator.comparing(Symbol::fqn)));
        return out;
    }

    private String keyOf(final Symbol s) {
        final var fqn = s.fqn() == null ? "" : s.fqn();
        final var dot = fqn.indexOf('.');
        if (dot > 0) {
            final var pkg = fqn.substring(0, dot);
            return pkg + "...*";
        }
        return "(root)";
    }

    private String sanitize(final String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String hash(final String content) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            final var sb = new StringBuilder(bytes.length * 2);
            for (final var b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.substring(0, 12);
        } catch (final NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}