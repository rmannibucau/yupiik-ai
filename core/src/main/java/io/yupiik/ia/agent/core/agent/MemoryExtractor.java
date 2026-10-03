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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MemoryExtractor {
    private static final Logger LOGGER = Logger.getLogger(MemoryExtractor.class.getName());
    private static final long DEBOUNCE_MS = 60_000;

    private final ChatClient chatClient;
    private final CodingAgentConfiguration configuration;
    private final MemoryStore memoryStore;
    private final JsonMapper mapper;
    private final VirtualThreadExecutor executor;
    private final AtomicLong lastRun = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Emitter emitter;
    private final Clock clock;
    private final UsageTracker usageTracker;
    private final String sessionId;
    private final MemoryCompactor memoryCompactor;

    public MemoryExtractor(
            final ChatClient chatClient,
            final CodingAgentConfiguration configuration,
            final MemoryStore memoryStore,
            final JsonMapper mapper,
            final VirtualThreadExecutor executor,
            final Emitter emitter,
            final Clock clock,
            final UsageTracker usageTracker,
            final String sessionId,
            final MemoryCompactor memoryCompactor) {
        this.chatClient = chatClient;
        this.configuration = configuration;
        this.memoryStore = memoryStore;
        this.mapper = mapper;
        this.executor = executor;
        this.emitter = emitter;
        this.clock = clock;
        this.usageTracker = usageTracker;
        this.sessionId = sessionId;
        this.memoryCompactor = memoryCompactor;
    }

    protected MemoryExtractor() {
        this.chatClient = null;
        this.configuration = null;
        this.memoryStore = null;
        this.mapper = null;
        this.executor = null;
        this.emitter = null;
        this.clock = null;
        this.usageTracker = null;
        this.sessionId = null;
        this.memoryCompactor = null;
    }

    public void maybeExtract(final List<Message> messages) {
        if (!configuration.memory().enabled()
                || configuration.memory().maxRatio() <= 0
                || configuration.memory().maxChars() <= 0) {
            return; // memory auto-disabled by budget ratio: never store facts that would not be injected
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
                notifyFailure("memory extraction failed: " + e.getMessage());
            } finally {
                if (memoryCompactor != null) {
                    memoryCompactor.maybeCompact();
                }
            }
        });
    }

    private void doExtract(final List<Message> messages) {
        final var sb = new StringBuilder();
        var count = 0;
        for (var i = messages.size() - 1; i >= 0 && count < 20; i--) {
            final var m = messages.get(i);
            final var others = m.others();
            if (others != null && Boolean.TRUE.equals(others.get("volatileTail"))) {
                continue; // synthetic session-state tail: never gain insights from it
            }
            count++;
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            sb.insert(
                    0, '[' + m.role() + "] " + (content.length() > 1500 ? content.substring(0, 1500) : content) + '\n');
        }
        final var excerpt = sb.toString();
        try {
            final var request = new ChatCompletionRequest(
                    null,
                    List.of(
                            Message.system(
                                    "Extract durable facts from this conversation excerpt: user preferences, project decisions, long lived knowledge. The response is constrained to the object {\"facts\": [{\"text\": string, \"scope\": \"global\" or \"project\"}]} by the output schema, max 3 facts. Use an empty facts array when nothing is worth remembering. EXCLUDE: transient task state, implementation details (\"X must be fixed / must round-trip\"), plans, undecided choices, and one-off session observations (benchmarks, sandbox paths, single-run results) that will be stale or superseded later. Keep each fact to one short sentence, at most 200 characters. User preferences and cross-project durable rules (\"always use tabs\", \"never edit generated files\") are scope \"global\"; project-specific decisions are scope \"project\"."),
                            Message.user(excerpt)),
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
            final var res = chatClient.chatStructured(request, "memory_facts", factsSchema());
            if (usageTracker != null && res.result() != null) {
                usageTracker.recordAuxiliary(res.result().usage());
            }
            if (res.structured()) {
                final var content =
                        res.result().message() == null || res.result().message().content() == null
                                ? ""
                                : String.valueOf(res.result().message().content());
                final var facts = parseFacts(content);
                if (!facts.isEmpty()) {
                    final var global = new java.util.ArrayList<String>();
                    final var project = new java.util.ArrayList<String>();
                    for (final var fact : facts) {
                        if ("global".equals(fact.scope())) {
                            global.add(fact.text());
                        } else {
                            project.add(fact.text());
                        }
                    }
                    if (!global.isEmpty()) {
                        memoryStore.append(true, global);
                    }
                    if (!project.isEmpty()) {
                        memoryStore.append(false, project);
                    }
                }
                resetFailures();
                return;
            }
            final var snippet = compressExcerpt(excerpt);
            if (snippet != null && !snippet.isBlank()) {
                memoryStore.appendText(false, snippet);
            }
            resetFailures();
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            notifyFailure("could not save memory: " + e.getMessage());
        }
    }

    private void notifyFailure(final String message) {
        final int failures = consecutiveFailures.incrementAndGet();
        if (failures == 1 && emitter != null) {
            emitter.emit(AgentEvent.Notice.warn(message, sessionId));
        }
    }

    private void resetFailures() {
        consecutiveFailures.set(0);
    }

    private static Map<String, Object> factsSchema() {
        final var itemProperties = Map.<String, Object>of(
                "text", Map.of("type", "string"),
                "scope", Map.of("type", "string"));
        final var item = Map.<String, Object>of(
                "type",
                "object",
                "properties",
                itemProperties,
                "required",
                List.of("text", "scope"),
                "additionalProperties",
                false);
        final var facts = Map.<String, Object>of("type", "array", "items", item, "additionalProperties", false);
        return Map.<String, Object>of(
                "type",
                "object",
                "properties",
                Map.of("facts", facts),
                "required",
                List.of("facts"),
                "additionalProperties",
                false);
    }

    private record Fact(String text, String scope) {}

    private List<Fact> parseFacts(final String content) {
        try {
            final var root = mapper.fromString(Object.class, content);
            if (!(root instanceof Map<?, ?> map) || !(map.get("facts") instanceof List<?> facts)) {
                return List.of();
            }
            final var out = new java.util.ArrayList<Fact>();
            for (final var raw : facts) {
                if (raw instanceof Map<?, ?> f) {
                    final var text = String.valueOf(f.get("text"));
                    final var scope = "global".equals(f.get("scope")) ? "global" : "project";
                    if (text != null && !text.equals("null") && !text.isBlank()) {
                        out.add(new Fact(text.strip(), scope));
                    }
                }
            }
            return out;
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
        final var res = chatClient.chat(new ChatCompletionRequest(
                null,
                List.of(
                        Message.system(
                                "Compress the following conversation excerpt into a concise, standalone memory snippet "
                                        + "capturing durable facts, decisions and user preferences. Keep long-lived knowledge only, "
                                        + "drop transient task state. Reply with only the snippet text, a few short sentences, no preamble."),
                        Message.user(excerpt)),
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null));
        if (usageTracker != null) {
            usageTracker.recordAuxiliary(res.usage());
        }
        final var content = res.message() == null || res.message().content() == null
                ? ""
                : String.valueOf(res.message().content());
        final var snippet = content.strip();
        return snippet.isEmpty() ? null : snippet;
    }
}
