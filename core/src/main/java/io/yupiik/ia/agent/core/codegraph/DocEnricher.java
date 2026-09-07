package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Optional, asynchronous, serialized LLM pass that produces a short {@code ## Summary} section per source
 * doc in the markdown doc tree. Runs on its own single-thread executor so only one indexation job executes
 * at a time (never two concurrent enricher runs), and never blocks the request thread that triggered it.
 * Per-file content hashes are cached so unchanged sources are not sent to the LLM again.
 */
public class DocEnricher implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(DocEnricher.class.getName());

    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;
    private final DocTreeWriter writer;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, String> summaryHashes = new ConcurrentHashMap<>();

    public DocEnricher(final CodingAgentConfiguration configuration, final ChatClient chatClient,
                       final DocTreeWriter writer) {
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.writer = writer;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            final var t = new Thread(task, "yca-doc-enricher");
            t.setDaemon(true);
            return t;
        });
    }

    protected DocEnricher() {
        this.configuration = null;
        this.chatClient = null;
        this.writer = null;
        this.executor = null;
    }

    public boolean enabled() {
        final var cfg = configuration.structuralCodeGraph();
        return configuration != null && cfg != null && cfg.enabled() && cfg.enrich() && writer != null;
    }

    /**
     * Schedule an async summary for a single parsed source file. Non-blocking and serialized: submissions
     * are queued on the single-thread executor and each file is skipped when its structural content hash is
     * unchanged since the last summary.
     */
    public void maybeEnrich(final String relSource, final GraphModel model) {
        if (!enabled() || model == null) {
            return;
        }
        final var hash = hash(model);
        final var previous = summaryHashes.get(relSource);
        if (hash.equals(previous)) {
            return;
        }
        executor.execute(() -> {
            try {
                enrich(relSource, model, hash);
            } catch (final RuntimeException e) {
                LOGGER.warning("Cannot enrich " + relSource + ": " + e.getMessage());
            }
        });
    }

    /**
     * Remove a source file from the enrichment cache (called when the file was deleted).
     */
    public void drop(final String relSource) {
        summaryHashes.remove(relSource);
    }

    private void enrich(final String relSource, final GraphModel model, final String hash) {
        if (!running.get()) {
            return;
        }
        final var summary = summarize(relSource, model);
        if (summary == null) {
            return;
        }
        writer.writeSummary(relSource, model, summary);
        summaryHashes.put(relSource, hash);
    }

    private String summarize(final String relSource, final GraphModel model) {
        // The enrich pass is intentionally kept to a single serialized request at a time so limited models
        // and providers (low rate limits, no batching) are not overloaded by concurrent indexation jobs.
        // If throughput becomes a concern this can be enhanced later (configurable concurrency / batching).
        final var prompt = new StringBuilder();
        prompt.append("You are summarizing a single source file for a coding agent.\n");
        prompt.append("File: ").append(relSource).append('\n');
        prompt.append("Symbols:\n");
        for (final var s : model.symbols()) {
            prompt.append("- ").append(s.fqn()).append(" (").append(s.kind()).append(')');
            if (s.signature() != null && !s.signature().isBlank()) {
                prompt.append(' ').append(s.signature());
            }
            prompt.append('\n');
        }
        prompt.append("Answer in a concise markdown summary of max 10 lines: purpose, key types and dependencies.\n");
        try {
            final var request = new ChatCompletionRequest(resolveModel(), List.of(Message.user(prompt.toString())),
                    (Double) null, false, null, null, 800, null, null);
            final var result = chatClient.chat(request);
            return result.message() == null ? null : text(result.message().content());
        } catch (final Exception e) {
            LOGGER.warning("Doc enrichment call failed for " + relSource + ": " + e.getMessage());
            return null;
        }
    }

    private String resolveModel() {
        final var override = configuration.structuralCodeGraph().model();
        return override == null || override.isBlank() ? configuration.provider().model() : override;
    }

    private static String text(final Object content) {
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

    private static String hash(final GraphModel model) {
        return Integer.toHexString(model.symbols().hashCode() * 31 + model.edges().hashCode());
    }

    @Override
    public void close() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}