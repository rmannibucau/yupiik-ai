package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class MemoryExtractor {
    private static final Logger LOGGER = Logger.getLogger(MemoryExtractor.class.getName());
    private static final long DEBOUNCE_MS = 60_000;

    private final ChatClient chatClient;
    private final CodingAgentConfiguration configuration;
    private final MemoryStore memoryStore;
    private final JsonMapper mapper;
    private final VirtualThreadExecutor executor;
    private final AtomicLong lastRun = new AtomicLong();
    private final Clock clock;

    public MemoryExtractor(final ChatClient chatClient, final CodingAgentConfiguration configuration,
                           final MemoryStore memoryStore, final JsonMapper mapper, final VirtualThreadExecutor executor,
                           final Clock clock) {
        this.chatClient = chatClient;
        this.configuration = configuration;
        this.memoryStore = memoryStore;
        this.mapper = mapper;
        this.executor = executor;
        this.clock = clock;
    }

    protected MemoryExtractor() {
        this.chatClient = null;
        this.configuration = null;
        this.memoryStore = null;
        this.mapper = null;
        this.executor = null;
        this.clock = null;
    }

    public void maybeExtract(final List<Message> messages) {
        if (!configuration.memory().enabled()) {
            return;
        }
        final var now = clock.millis();
        final var last = lastRun.get();
        if (now - last < DEBOUNCE_MS || !lastRun.compareAndSet(last, now)) {
            return;
        }
        executor.execute(() -> {
            try {
                doExtract(messages);
            } catch (final RuntimeException e) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "Memory extraction failed", e);
                }
            }
        });
    }

    private void doExtract(final List<Message> messages) {
        final var sb = new StringBuilder();
        var count = 0;
        for (var i = messages.size() - 1; i >= 0 && count < 20; i--, count++) {
            final var m = messages.get(i);
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            sb.insert(0, '[' + m.role() + "] " + (content.length() > 1500 ? content.substring(0, 1500) : content) + '\n');
        }
        final var excerpt = sb.toString();
        try {
            final var request = new ChatCompletionRequest(null, List.of(
                    Message.system("Extract durable facts from this conversation excerpt: user preferences, project decisions, long lived knowledge. The response is constrained to the object {\"facts\": [short sentence strings]} by the output schema, max 5 facts. Use an empty facts array when nothing is worth remembering. Do not include transient task state."),
                    Message.user(excerpt)), null, false, null, null, null, null, null);
            final var res = chatClient.chatStructured(request, "memory_facts", factsSchema());
            if (res.structured()) {
                final var content = res.result().message() == null || res.result().message().content() == null
                        ? "" : String.valueOf(res.result().message().content());
                final var facts = parseFacts(content);
                if (!facts.isEmpty()) {
                    memoryStore.append(false, facts);
                }
                return;
            }
            final var snippet = compressExcerpt(excerpt);
            if (snippet != null && !snippet.isBlank()) {
                memoryStore.appendText(false, snippet);
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Object> factsSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("facts", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "additionalProperties", false)),
                "required", List.of("facts"),
                "additionalProperties", false);
    }

    private List<String> parseFacts(final String content) {
        try {
            final var root = mapper.fromString(Object.class, content);
            if (!(root instanceof Map<?, ?> map) || !(map.get("facts") instanceof List<?> facts)) {
                return List.of();
            }
            return facts.stream().map(String::valueOf).toList();
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINEST, "Unparsable structured memory facts: " + content, e);
            return List.of();
        }
    }

    /**
     * Fallback when the provider does not support structured output: ask a plain completion to
     * compress the excerpt into a concise, standalone memory snippet and return it as raw text.
     * Returns {@code null} when the compression call fails.
     */
    private String compressExcerpt(final String excerpt) throws IOException, InterruptedException {
        final var res = chatClient.chat(new ChatCompletionRequest(null, List.of(
                Message.system("Compress the following conversation excerpt into a concise, standalone memory snippet "
                        + "capturing durable facts, decisions and user preferences. Keep long-lived knowledge only, "
                        + "drop transient task state. Reply with only the snippet text, a few short sentences, no preamble."),
                Message.user(excerpt)), null, false, null, null, null, null, null));
        final var content = res.message() == null || res.message().content() == null
                ? "" : String.valueOf(res.message().content());
        final var snippet = content.strip();
        return snippet.isEmpty() ? null : snippet;
    }
}
