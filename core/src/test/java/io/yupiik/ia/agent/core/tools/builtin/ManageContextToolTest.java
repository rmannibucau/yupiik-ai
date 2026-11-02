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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ManageContextToolTest {
    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private ToolContext context(final ContextView view) {
        return new ToolContext("s1", null, null, null, null, null, null, tracker(), view, Cancellations.NEVER);
    }

    private ProviderManager providerManager() {
        return new ProviderManager(config(true), mapper);
    }

    @Test
    void statusUsesThePublishedSnapshot() {
        final var view = new ContextView();
        view.publish(new ContextView.Snapshot(12, 5_000, 100_000, 80_000, 62.5, 65, 80));
        final var tool = new ManageContextTool(config(true), mapper, providerManager());
        final var result = tool.execute(Map.of("action", "status"), context(view));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("\"messageCount\":12"), result.output());
        assertTrue(result.output().contains("\"rawTokens\":5000"), result.output());
    }

    @Test
    void statusWithoutSnapshotReportsWindowOnly() {
        final var view = new ContextView();
        final var tool = new ManageContextTool(config(true), mapper, providerManager());
        final var result = tool.execute(Map.of("action", "status"), context(view));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("contextWindow"), result.output());
        assertTrue(result.output().contains("\"messageCount\":0"), result.output());
    }

    @Test
    void compactRequestIsAllowedBelowTheFloor() {
        final var view = new ContextView();
        view.publish(new ContextView.Snapshot(4, 500, 100_000, 80_000, 10.0, 65, 80));
        final var tool = new ManageContextTool(config(true), mapper, providerManager());
        final var result = tool.execute(Map.of("action", "compact"), context(view));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().startsWith("Compaction scheduled"), result.output());
        assertTrue(view.poll().force(), "a manual compact bypasses the usage guard");
    }

    @Test
    void compactRequestQueuesADirective() {
        final var view = new ContextView();
        view.publish(new ContextView.Snapshot(40, 70_000, 100_000, 80_000, 90.0, 65, 80));
        final var tool = new ManageContextTool(config(true), mapper, providerManager());
        final var args = new LinkedHashMap<String, Object>();
        args.put("action", "compact");
        args.put("focus", "keep the migration plan");
        args.put("keep_last", 4);
        final var result = tool.execute(args, context(view));
        assertTrue(result.success(), result.output());
        assertEquals(new ContextView.Directive("compact", "keep the migration plan", 4, "compress", true), view.poll());
    }

    @Test
    void compressSummarizesAClosedRange() {
        final var cfg = config(true);
        final var view = new ContextView(cfg.compression());
        final var messages = List.of(
                Message.system("system"),
                Message.user("first task"),
                Message.assistant("done", List.of()),
                Message.user("next task"));
        view.publishMessages(messages);
        view.compression().renderRefs(messages); // assigns stable refs like the agent loop does
        final var tool = new ManageContextTool(cfg, mapper, providerManager());
        final var result = tool.execute(
                Map.of(
                        "action",
                        "compress",
                        "topic",
                        "first task",
                        "content",
                        List.of(Map.of("startId", "1", "endId", "2", "summary", "first task was completed"))),
                context(view));
        assertTrue(result.success(), result.output());
        assertEquals(1, view.compression().blocks().size());
        assertEquals(4, messages.size(), "the transcript itself must never be mutated");
        assertEquals(3, view.compression().rewrite(messages).size(), "the block replaces the two-message span");
    }

    @Test
    void secondRequestIsRejectedWhileOneIsPending() {
        final var view = new ContextView();
        view.publish(new ContextView.Snapshot(40, 70_000, 100_000, 80_000, 90.0, 65, 80));
        final var tool = new ManageContextTool(config(true), mapper, providerManager());
        assertTrue(view.request(new ContextView.Directive("prune", null, 0, "test")));
        final var result = tool.execute(Map.of("action", "compact"), context(view));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("already pending"), result.output());
        assertEquals("prune", view.poll().action(), "the first queued directive must survive");
    }

    @Test
    void disabledToolErrors() {
        final var view = new ContextView();
        final var tool = new ManageContextTool(config(false), mapper, providerManager());
        final var result = tool.execute(Map.of("action", "status"), context(view));
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("disabled"), result.output());
    }

    private CodingAgentConfiguration config(final boolean enabled) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", "100000");
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        if (!enabled) {
            entries.put("yca.tools.manageContext.enabled", "false");
        }
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
    }

    private UsageTracker tracker() {
        return new UsageTracker(new ProviderManager(config(true), mapper), null, null, Clock.systemUTC());
    }
}
