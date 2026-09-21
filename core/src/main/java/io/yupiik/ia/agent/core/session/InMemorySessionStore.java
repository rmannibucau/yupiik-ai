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
package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.Message;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store for subagents. No persistence, purely ephemeral.
 * Thread-safe for concurrent access from multiple subagents.
 */
public class InMemorySessionStore implements SessionStore {
    private final JsonMapper mapper;
    private final Clock clock;
    private final Map<String, List<SessionEntry>> sessions = new ConcurrentHashMap<>();

    public InMemorySessionStore(final JsonMapper mapper, final Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public InMemorySessionStore() {
        this(null, null);
    }

    @Override
    public String create() {
        var id = "mem-" + System.nanoTime();
        while (sessions.containsKey(id)) {
            id = "mem-" + System.nanoTime();
        }
        sessions.put(id, new ArrayList<>());
        return id;
    }

    @Override
    public void append(final String id, final SessionEntry entry) {
        sessions.computeIfAbsent(id, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public List<Message> load(final String id) {
        final var entries = sessions.get(id);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(e -> e.role() != null)
                .map(e -> new Message(e.role(), e.content(), e.name(), e.toolCalls(), e.toolCallId(), null))
                .toList();
    }

    @Override
    public void replace(final String id, final List<Message> messages) {
        final var entries = messages.stream()
                .map(m -> SessionEntry.of(
                        clock != null ? clock.millis() : System.currentTimeMillis(),
                        m.role(),
                        m.content(),
                        m.toolCalls(),
                        m.toolCallId()))
                .toList();
        sessions.put(id, new ArrayList<>(entries));
    }

    @Override
    public boolean isEmpty(final String id) {
        final var messages = load(id);
        if (messages.isEmpty()) {
            return true;
        }
        return messages.size() == 1 && "user".equals(messages.get(0).role());
    }

    @Override
    public void delete(final String id) {
        sessions.remove(id);
    }

    @Override
    public String summary(final String id) {
        return load(id).stream()
                .filter(m -> "user".equals(m.role())
                        && m.content() instanceof final String text
                        && !text.isBlank()
                        && !text.startsWith("<compacted-context>")
                        && !text.startsWith("<pruned-context>"))
                .map(m -> (String) m.content())
                .map(AgentSessionService::firstLine)
                .findFirst()
                .orElse("");
    }

    /**
     * Returns all session IDs currently in memory.
     */
    public List<String> listIds() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * Returns the number of entries in a session.
     */
    public int size(final String id) {
        final var entries = sessions.get(id);
        return entries == null ? 0 : entries.size();
    }

    /**
     * Clears all sessions.
     */
    public void clear() {
        sessions.clear();
    }
}
