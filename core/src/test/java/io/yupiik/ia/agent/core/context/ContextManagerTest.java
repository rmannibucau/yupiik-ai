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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextManagerTest {

    private static CodingAgentConfiguration config(final int keepRecent) {
        return config(keepRecent, 128000);
    }

    private static CodingAgentConfiguration config(final int keepRecent, final int window) {
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", "x"),
                Map.entry("yca.workingDir", "."),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1"),
                Map.entry("yca.provider.providers.0.apiKey", ""),
                Map.entry("yca.provider.providers.0.model", "m"),
                Map.entry("yca.provider.providers.0.api", "auto"),
                Map.entry("yca.provider.providers.0.contextWindow", String.valueOf(window)),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "120"),
                Map.entry("yca.pruning.keepRecent", String.valueOf(keepRecent))));
    }

    @Test
    void compactForOverflowIsNoOpOnSmallConversations() {
        final var cfg = config(6);
        final var manager = new ContextManager(
                cfg,
                new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                new CompactionCoordinator(
                        cfg,
                        null,
                        new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                        providerManager(cfg),
                        null));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 3; i++) {
            messages.add(Message.user("step " + i));
        }
        final var result = manager.compactForOverflow(messages);
        assertSame(messages, result.messages(), "a conversation that already fits the keep window must stay untouched");
        assertEquals(CompactionResult.Mode.NONE, result.mode());
    }

    @Test
    void compactKeepsTheDigestWholeWhenShrinkingTheTailOverflow() throws Exception {
        final var digest = "summary: " + "x".repeat(1_200);
        final var cfg = config(6, 3_000);
        final var chat = new ChatClient() {
            @Override
            public ChatResult chat(final ChatCompletionRequest request) {
                return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
            }
        };
        final var manager = new ContextManager(
                cfg,
                new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                new CompactionCoordinator(
                        cfg,
                        chat,
                        new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                        providerManager(cfg),
                        null));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 7; i++) {
            messages.add(Message.user("step " + i));
        }
        messages.add(Message.tool("t8", "a tool output " + "y".repeat(20_000)));
        final var result = manager.compact(messages);
        assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
        final var summary = result.messages().stream()
                .filter(m -> m.content() instanceof String s && s.contains("<compacted-context>"))
                .findFirst()
                .orElseThrow();
        assertTrue(
                summary.content() instanceof String s && s.contains(digest),
                "the generated digest must survive overflow shrinking intact");
        for (final var m : result.messages()) {
            if (m.content() instanceof String s && s.contains("a tool output")) {
                assertTrue(
                        s.length() < 20_000 && s.contains("elided"),
                        "the overflowing tail tool output must be spliced down");
            }
        }
        final var first = result.messages().get(0);
        assertEquals("system", first.role());
        assertEquals("system", first.content());
    }

    @Test
    void toolSchemasCountTowardTheTriggers() {
        final var cfg = config(6);
        final var manager = new ContextManager(
                cfg,
                new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                new CompactionCoordinator(
                        cfg,
                        null,
                        new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                        providerManager(cfg),
                        null));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 3; i++) {
            messages.add(Message.user("step " + i));
        }
        assertFalse(
                manager.pruneNeeded(messages),
                "a tiny conversation is far below the pruning threshold without tool schemas");
        assertTrue(
                manager.pruneNeeded(messages, 150_000), "the tool-schema tokens must count toward the pruning trigger");
        assertTrue(
                manager.compactNeeded(messages, 150_000),
                "the tool-schema tokens must count toward the compaction trigger");
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(
                cfg, new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> Optional.empty()));
    }
}
