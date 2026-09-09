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
import java.util.concurrent.atomic.AtomicLong;
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
                LOGGER.warning("Memory extraction failed: " + e.getMessage());
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
        try {
            final var res = chatClient.chat(new ChatCompletionRequest(null, List.of(
                    Message.system("Extract durable facts from this conversation excerpt: user preferences, project decisions, long lived knowledge. Reply with a JSON array of short sentences (max 5). Reply with [] when nothing is worth remembering. Do not include transient task state."),
                    Message.user(sb.toString())), null, false, null, null, null, null, null));
            final var content = res.message() == null || res.message().content() == null ? "" : String.valueOf(res.message().content());
            final var start = content.indexOf('[');
            final var end = content.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return;
            }
            final var array = mapper.fromString(Object.class, content.substring(start, end + 1));
            if (array instanceof List<?> list && !list.isEmpty()) {
                memoryStore.append(false, list.stream().map(String::valueOf).toList());
            }
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
