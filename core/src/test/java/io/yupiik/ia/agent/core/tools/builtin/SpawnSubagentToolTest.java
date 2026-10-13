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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.agent.SubagentRunner;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpawnSubagentToolTest {
    @TempDir
    Path storageHome;

    private CodingAgentConfiguration config(final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.subagents.enabled", "true");
        entries.put("yca.subagents.maxConcurrent", "2");
        entries.put("yca.llm.maxConcurrent", "4");
        entries.putAll(overrides);
        return TestConfigs.codingAgent(entries);
    }

    private ProviderManager providerManager(final int maxConcurrent) {
        return new ProviderManager() {
            @Override
            public ProviderConfiguration current() {
                return new ProviderConfiguration(
                        "default",
                        "http://test",
                        "key",
                        "model",
                        ApiFlavor.AUTO,
                        -1,
                        -1,
                        "",
                        null,
                        List.of(),
                        maxConcurrent,
                        300,
                        "",
                        Map.of(),
                        Map.of(),
                        "",
                        null);
            }
        };
    }

    private SubagentRegistry registry(final CodingAgentConfiguration config) {
        return new SubagentRegistry(config, providerManager(4));
    }

    private SubagentRunner mockRunner(final SubagentRegistry registry) {
        return new SubagentRunner(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                registry,
                new PrecisionDurationMarkerProvider()) {
            @Override
            public void runAsync(
                    final SubagentRegistry.SubagentState state,
                    final SessionStore sessionStore,
                    final SnapshotStore snapshotStore,
                    final TodoStore todoStore,
                    final ToolContext parentContext,
                    final BooleanSupplier cancelled) {
                registry.complete(
                        state.id(),
                        SubagentRegistry.SubagentResult.success("mock result", "conv", List.of("artifact")));
            }
        };
    }

    private SessionStore noopStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
    }

    private SnapshotStore noopSnapshotStore() {
        return new SnapshotStore() {
            @Override
            public synchronized Checkpoint save(final String sessionId, final String label) {
                return new Checkpoint(label, 0);
            }

            @Override
            public synchronized Retained restore(final String sessionId, final String summary) {
                return new Retained(summary);
            }

            @Override
            public synchronized void cancel(final String sessionId) {}

            @Override
            public String checkpointLabel(final String sessionId) {
                return null;
            }

            @Override
            public List<Retained> retained(final String sessionId) {
                return List.of();
            }
        };
    }

    private ToolContext context(final TodoStore todos) {
        return new ToolContext("s1", null, null, null, null, todos, null, null, null, Cancellations.NEVER);
    }

    private TodoStore noopTodoStore() {
        return new TodoStore() {
            @Override
            public List<TodoItem> all() {
                return List.of();
            }

            @Override
            public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                return items;
            }
        };
    }

    @Test
    void executeReturnsSubagentIdWhenStarted() {
        final var cfg = config(Map.of());
        final var reg = registry(cfg);
        final var tool = new SpawnSubagentTool(reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg);
        final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("Sub-agent started"));
        assertTrue(result.output().contains("do work"));
    }

    @Test
    void executeFailsWhenRegistryDisabled() {
        final var cfg = config(Map.of("yca.subagents.enabled", "false"));
        final var reg = registry(cfg);
        final var tool = new SpawnSubagentTool(reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg);
        final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("disabled"));
    }

    @Test
    void executeFailsWhenTaskMissing() {
        final var cfg = config(Map.of());
        final var reg = registry(cfg);
        final var tool = new SpawnSubagentTool(reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg);
        final var result = tool.execute(Map.of(), context(noopTodoStore()));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("task is required"));
    }

    @Test
    void forwardsTheParentTurnCancellationProbe() {
        // an interrupt on the parent turn must reach the sub-agent: it would otherwise keep
        // burning tokens on an abandoned task (the registry latch stays available for a
        // model-driven cancel_subagent)
        final var cfg = config(Map.of());
        final var reg = registry(cfg);
        final var forwarded = new AtomicReference<BooleanSupplier>();
        final var runner =
                new SubagentRunner(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reg,
                        new PrecisionDurationMarkerProvider()) {
                    @Override
                    public void runAsync(
                            final SubagentRegistry.SubagentState state,
                            final SessionStore sessionStore,
                            final SnapshotStore snapshotStore,
                            final TodoStore todoStore,
                            final ToolContext parentContext,
                            final BooleanSupplier cancelled) {
                        forwarded.set(cancelled);
                        reg.complete(
                                state.id(), SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                    }
                };
        final var parentCancelled = new AtomicBoolean(false);
        final var tool = new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg);
        final var ctx =
                new ToolContext("s1", null, null, null, null, noopTodoStore(), null, null, null, parentCancelled::get);

        final var result = tool.execute(Map.of("task", "do work"), ctx);
        assertTrue(result.success(), result.output());
        final var probe = forwarded.get();
        assertNotNull(probe, "the sub-agent must receive a probe");
        assertFalse(probe.getAsBoolean(), "a running turn is not cancelled");

        parentCancelled.set(true);
        assertTrue(probe.getAsBoolean(), "the parent's interrupt must be visible to the sub-agent");
    }

    @Test
    void executeFailsWhenConcurrencyExhausted() {
        final var cfg = config(Map.of("yca.subagents.maxConcurrent", "1"));
        final var reg = registry(cfg);
        // Fill the semaphore directly
        reg.start("parent", "t1").orElseThrow();
        final var tool = new SpawnSubagentTool(reg, mockRunner(reg), noopStore(), noopSnapshotStore(), cfg);
        final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("maximum concurrent sub-agents reached"));
    }

    @Test
    void forwardsExplicitModelToTheSubagentState() {
        final var cfg = config(Map.of());
        final var reg = registry(cfg);
        final var captured = new AtomicReference<String>();
        final var runner =
                new SubagentRunner(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reg,
                        new PrecisionDurationMarkerProvider()) {
                    @Override
                    public void runAsync(
                            final SubagentRegistry.SubagentState state,
                            final SessionStore sessionStore,
                            final SnapshotStore snapshotStore,
                            final TodoStore todoStore,
                            final ToolContext parentContext,
                            final BooleanSupplier cancelled) {
                        captured.set(state.model());
                        reg.complete(
                                state.id(), SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                    }
                };
        final var tool = new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg);
        final var result =
                tool.execute(Map.of("task", "do work", "model", "$local/deepseek-v4-flash"), context(noopTodoStore()));
        assertTrue(result.success(), result.output());
        assertEquals("$local/deepseek-v4-flash", captured.get(), "the explicit model must reach the subagent state");
    }

    @Test
    void leavesModelNullWhenNotRequested() {
        final var cfg = config(Map.of());
        final var reg = registry(cfg);
        final var captured = new AtomicReference<String>();
        final var runner =
                new SubagentRunner(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reg,
                        new PrecisionDurationMarkerProvider()) {
                    @Override
                    public void runAsync(
                            final SubagentRegistry.SubagentState state,
                            final SessionStore sessionStore,
                            final SnapshotStore snapshotStore,
                            final TodoStore todoStore,
                            final ToolContext parentContext,
                            final BooleanSupplier cancelled) {
                        captured.set(state.model());
                        reg.complete(
                                state.id(), SubagentRegistry.SubagentResult.success("mock result", "conv", List.of()));
                    }
                };
        final var tool = new SpawnSubagentTool(reg, runner, noopStore(), noopSnapshotStore(), cfg);
        final var result = tool.execute(Map.of("task", "do work"), context(noopTodoStore()));
        assertTrue(result.success(), result.output());
        assertNull(captured.get(), "no explicit model means inherit (null on the state)");
    }
}
