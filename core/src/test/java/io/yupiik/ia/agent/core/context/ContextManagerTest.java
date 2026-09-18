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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ContextManagerTest {

    private static CodingAgentConfiguration config(final int keepRecent) {
        return config(keepRecent, 128000);
    }

    private static CodingAgentConfiguration config(final int keepRecent, final int window) {
        return config(keepRecent, window, Map.of());
    }

    private static CodingAgentConfiguration config(
            final int keepRecent, final int window, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", String.valueOf(window));
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.pruning.keepRecent", String.valueOf(keepRecent));
        entries.putAll(extra);
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(entries);
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
        final var cfg = config(5, 3_000, Map.of("yca.compaction.tailBudgetTokens", "1"));
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

    @Test
    void pruneClearsOnlyOldToolOutputsAndProtectsRecentTurns() {
        final var cfg = config(
                2,
                8_000,
                Map.ofEntries(
                        Map.entry("yca.pruning.protectTurns", "1"),
                        Map.entry("yca.pruning.minPruneTokens", "1000"),
                        Map.entry("yca.pruning.protectRecentTokens", "1000")));
        final var manager = manager(cfg, null);
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        messages.add(Message.user("goal"));
        messages.add(Message.tool("t1", "old output " + "x".repeat(6_000)));
        messages.add(Message.tool("t2", "old output " + "y".repeat(6_000)));
        messages.add(Message.user("follow-up"));
        messages.add(Message.assistant("working", null));
        messages.add(Message.tool("t3", "recent output " + "z".repeat(4_000)));
        final var result = manager.prune(messages);
        assertEquals(CompactionResult.Mode.CLEARED, result.mode());
        assertEquals(
                CompactionCoordinator.PRUNE_CLEARED_MARKER,
                result.messages().get(2).content(),
                "the oldest tool output must be cleared");
        assertEquals("t1", result.messages().get(2).toolCallId());
        assertTrue(
                result.messages().get(3).content().toString().startsWith("old output"),
                "the newest old tool output is inside the protected token budget");
        assertTrue(
                result.messages().get(6).content().toString().startsWith("recent output"),
                "the most recent turn's tool output must stay untouched");
        assertEquals("goal", result.messages().get(1).content());
    }

    @Test
    void pruneIsNoOpWhenPrunableVolumeIsBelowTheMinimum() {
        final var cfg = config(
                2,
                8_000,
                Map.ofEntries(
                        Map.entry("yca.pruning.protectTurns", "1"),
                        Map.entry("yca.pruning.minPruneTokens", "100000"),
                        Map.entry("yca.pruning.protectRecentTokens", "10")));
        final var manager = manager(cfg, null);
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        messages.add(Message.user("goal"));
        messages.add(Message.tool("t1", "small old output"));
        messages.add(Message.user("follow-up"));
        messages.add(Message.assistant("working", null));
        messages.add(Message.tool("t2", "recent output"));
        final var result = manager.prune(messages);
        assertEquals(CompactionResult.Mode.NONE, result.mode());
        assertSame(messages, result.messages(), "below the churn minimum the history must stay untouched");
    }

    @Test
    void compactNeverSplitsAssistantToolCallFromItsResults() throws Exception {
        final var cfg = config(5, 16_000, Map.of("yca.compaction.tailBudgetTokens", "1"));
        final var manager = manager(cfg, chatReturning("digest"));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        messages.add(Message.user("goal"));
        messages.add(Message.assistant("ack", null));
        messages.add(Message.user("next step"));
        messages.add(Message.assistant(
                "calling tools",
                List.of(new io.yupiik.ia.agent.core.llm.ToolCall(
                        "t1", "function", new io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall("bash", "{}"), 0))));
        messages.add(Message.tool("t1", "tool result " + "b".repeat(2_000)));
        messages.add(Message.tool("t2", "tool result " + "c".repeat(2_000)));
        messages.add(Message.user("third step"));
        messages.add(Message.assistant("working", null));
        final var result = manager.compact(messages);
        assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
        assertEquals(7, result.messages().size(), "system, digest and the whole kept tail");
        assertEquals("assistant", result.messages().get(2).role());
        assertEquals("tool", result.messages().get(3).role());
        assertEquals("t1", result.messages().get(3).toolCallId());
        assertEquals("tool", result.messages().get(4).role());
        assertEquals("user", result.messages().get(5).role());
        assertEquals("assistant", result.messages().get(6).role());
        for (var i = 1; i < result.messages().size(); i++) {
            if ("tool".equals(result.messages().get(i).role())) {
                assertTrue(
                        "assistant".equals(result.messages().get(i - 1).role())
                                || "tool".equals(result.messages().get(i - 1).role()),
                        "a tool result must never be separated from its assistant tool call");
            }
        }
    }

    @Test
    void compactReplaysTheLastUserMessageWhenTheTailMissesIt() throws Exception {
        final var cfg = config(2, 32_000, Map.of("yca.compaction.tailBudgetTokens", "1"));
        final var manager = manager(cfg, chatReturning("digest"));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        messages.add(Message.user("first task " + "a".repeat(2_000)));
        messages.add(Message.assistant("ack", null));
        messages.add(Message.user("second task " + "a".repeat(2_000)));
        messages.add(Message.assistant("ack", null));
        messages.add(Message.user("latest task"));
        messages.add(Message.tool("t1", "tool result " + "b".repeat(2_000)));
        messages.add(Message.tool("t2", "tool result " + "b".repeat(2_000)));
        messages.add(Message.tool("t3", "tool result " + "c".repeat(2_000)));
        final var result = manager.compact(messages);
        assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
        final var last = result.messages().get(result.messages().size() - 1);
        assertEquals("user", last.role());
        assertEquals("latest task", last.content(), "the current task must be replayed after the summary");
    }

    @Test
    void compactReanchorsOnThePreviousSummaryAndAsksForTheStructuredTemplate() throws Exception {
        final var cfg = config(2, 128_000, Map.of("yca.compaction.tailBudgetTokens", "2000"));
        final var captured = new ArrayList<List<Message>>();
        final var manager = manager(cfg, chatRecording("updated digest", captured));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        messages.add(
                Message.user("<compacted-context>\nold summary\n</compacted-context>\n\nContinue from this summary."));
        messages.add(Message.user("new work " + "q".repeat(2_000)));
        messages.add(Message.assistant("done", null));
        messages.add(Message.user("more work " + "q".repeat(2_000)));
        messages.add(Message.assistant("done", null));
        messages.add(Message.user("even more " + "q".repeat(2_000)));
        messages.add(Message.assistant("done", null));
        messages.add(Message.user("current task"));
        final var result = manager.compact(messages);
        assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
        final var prompt = captured.get(0).stream()
                .filter(m -> "user".equals(m.role()))
                .map(m -> String.valueOf(m.content()))
                .findFirst()
                .orElseThrow();
        assertTrue(prompt.contains("<prior-summary>"), "a previous summary must anchor the new one");
        assertTrue(prompt.contains("old summary"));
        assertTrue(prompt.contains("## Objective"), "the structured template must be requested");
        assertTrue(
                captured.get(0).get(0).content().toString().contains("structured summary"),
                "the summarizer system prompt must describe the role");
    }

    private static ContextManager manager(final CodingAgentConfiguration cfg, final ChatClient chat) {
        return new ContextManager(
                cfg,
                new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                new CompactionCoordinator(
                        cfg,
                        chat,
                        new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                        providerManager(cfg),
                        null));
    }

    private static ChatClient chatReturning(final String digest) {
        return new ChatClient(null, null, null, Clock.systemUTC(), null) {
            @Override
            public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled) {
                return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
            }
        };
    }

    private static ChatClient chatRecording(final String digest, final List<List<Message>> captured) {
        return new ChatClient(null, null, null, Clock.systemUTC(), null) {
            @Override
            public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled) {
                captured.add(List.copyOf(request.messages()));
                return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
            }
        };
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(
                cfg, new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> Optional.empty()));
    }
}
