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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class CheckSubagentsToolTest {
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

    @Test
    void pollReturnsEmptyListWhenNoSubagents(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "poll"));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"subagents\":[]"));
    }

    @Test
    void pollReturnsActiveSubagentsWithStatus(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        reg.start("parent", "task1");
        reg.start("parent", "task2");
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "poll"));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("running"));
        assertTrue(result.output().contains("task1"));
        assertTrue(result.output().contains("task2"));
    }

    @Test
    void collectReturnsSummaryWhenCompleted(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var state = reg.start("parent", "task").orElseThrow();
        reg.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "full", List.of("artifact")));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"completed\":true"));
        assertTrue(result.output().contains("done"));
    }

    @Test
    void collectFailsWhenStillRunning(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var state = reg.start("parent", "task").orElseThrow();
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("still running"));
    }

    @Test
    void collectFailsWhenNotFound(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "collect", "subagent_id", "unknown"));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("not found"));
    }

    @Test
    void cancelTerminatesRunningSubagent(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var state = reg.start("parent", "task").orElseThrow();
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "cancel", "subagent_id", state.id()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"cancelled\":true"));
        assertTrue(reg.get(state.id()).isEmpty());
    }

    @Test
    void cancelNoopWhenAlreadyCompleted(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var state = reg.start("parent", "task").orElseThrow();
        reg.complete(state.id(), SubagentRegistry.SubagentResult.success("done", "full", List.of()));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "cancel", "subagent_id", state.id()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"cancelled\":false"));
        assertTrue(result.output().contains("already completed"));
    }

    @Test
    void executeFailsWhenDisabled(final JsonMapper mapper) {
        final var reg = registry(config(Map.of("yca.subagents.enabled", "false")));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "poll"));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("disabled"));
    }

    @Test
    void executeFailsWhenActionMissing(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of());
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("action is required"));
    }

    @Test
    void executeFailsWhenInvalidAction(final JsonMapper mapper) {
        final var reg = registry(config(Map.of()));
        final var tool = new CheckSubagentsTool(reg, mapper);
        final var result = tool.execute(Map.of("action", "invalid"));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("Invalid action"));
    }
}
