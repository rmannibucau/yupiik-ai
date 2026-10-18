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

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process push channel that carries a finished sub-agent's result to its <em>parent</em> turn.
 *
 * <p>The parent {@code AgentLoop} (the one where {@code subagentId == null}) drains its session-id
 * queue at the round head, right next to the queued-prompt integration, and injects the completion as a
 * {@code user}-role {@code <subagent ...>...</subagent>} message so the parent model's <em>next round</em>
 * automatically sees the finished result. This is what removes the need for the model to poll
 * {@code check_subagents}: the completion is pushed, not fetched.</p>
 *
 * <p>Keyed by the <em>parent</em> session id ({@code SubagentState#parentSessionId()}), which both the
 * parent and a child AgentLoop share as their {@code currentSessionId}; only the parent (agent with a
 * null {@code subagentId}) drains, so a completion routes to the spawning turn, never to a nested
 * child. Deliveries that arrive while the parent turn is not live are held until the parent's next turn
 * drains them, so a child finishing between turns is never lost.</p>
 */
public final class SubagentDeliveryBus {
    private static final SubagentDeliveryBus INSTANCE = new SubagentDeliveryBus();

    private final ConcurrentHashMap<String, ArrayDeque<Delivery>> pending = new ConcurrentHashMap<>();

    /** A finished sub-agent's outcome, to be injected into the parent context. */
    public record Delivery(String subagentId, String name, String summary, boolean success, boolean cancelled) {
        /** The injected message text, matching opencode's tagged {@code <subagent>} shape. */
        public String message() {
            final var state = cancelled ? "cancelled" : success ? "completed" : "failed";
            final var text = summary == null || summary.isBlank() ? "[no summary]" : summary;
            return "<subagent sessionID=\"" + subagentId + "\" state=\"" + state + "\" description=\""
                    + (name == null ? "" : name.replace("\"", "'")) + "\">\n" + text + "\n</subagent>";
        }
    }

    private SubagentDeliveryBus() {
        // static process-global bus
    }

    public static SubagentDeliveryBus get() {
        return INSTANCE;
    }

    /** Delivers a finished sub-agent result to its parent session (idempotent per subagent id). */
    public void deliver(final String parentSessionId, final Delivery delivery) {
        if (parentSessionId == null || parentSessionId.isBlank() || delivery == null) {
            return;
        }
        pending.compute(parentSessionId, (k, queue) -> {
            final var q = queue == null ? new ArrayDeque<Delivery>() : queue;
            // idempotency: a single completion may fan out (e.g. push + a concurrent collect)
            if (q.stream().noneMatch(d -> d.subagentId().equals(delivery.subagentId()))) {
                q.addLast(delivery);
            }
            return q;
        });
    }

    /** Drains and returns the pending deliveries for a parent session (removing them from the queue). */
    public List<Delivery> drain(final String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return List.of();
        }
        final var removed = pending.remove(parentSessionId);
        if (removed == null || removed.isEmpty()) {
            return List.of();
        }
        return List.copyOf(removed);
    }
}
