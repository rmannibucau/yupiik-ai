package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CompactionConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.configuration.PruningConfiguration;
import io.yupiik.ia.agent.core.configuration.ToolsConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagerTest {

    private static CodingAgentConfiguration config(final int keepRecent) {
        return config(keepRecent, 128000);
    }

    private static CodingAgentConfiguration config(final int keepRecent, final int window) {
        final var provider = new ProviderConfiguration("default", "https://api.openai.com/v1", "", "m", null, null, window, -1, List.of(), 120, "", java.util.Map.of(), null);
        return new CodingAgentConfiguration("x", ".", null,
                new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, window, -1, List.of(), 120),
                new ModelConfiguration("", ""),
                new LlmConfiguration(3, 500L, 0, 4, true, 250),
                new CompactionConfiguration(true, 80),
                new PruningConfiguration(true, 65, keepRecent, 80),
                null, null, null, null, null, null, null, null, null,
                io.yupiik.ia.agent.core.configuration.HttpClientConfiguration.defaultConfig(),
                io.yupiik.ia.agent.core.configuration.ToolsConfiguration.defaultConfig());
    }

    @Test
    void compactForOverflowIsNoOpOnSmallConversations() {
        final var cfg = config(6);
        final var manager = new ContextManager(cfg, new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                new CompactionCoordinator(cfg, null, new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                        providerManager(cfg), null));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 3; i++) {
            messages.add(Message.user("step " + i));
        }
        final var result = manager.compactForOverflow(messages);
        assertSame(messages, result.messages(),
                "a conversation that already fits the keep window must stay untouched");
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
        final var manager = new ContextManager(cfg, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                new CompactionCoordinator(cfg, chat, new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                        providerManager(cfg), null));
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
                .findFirst().orElseThrow();
        assertTrue(summary.content() instanceof String s && s.contains(digest),
                "the generated digest must survive overflow shrinking intact");
        for (final var m : result.messages()) {
            if (m.content() instanceof String s && s.contains("a tool output")) {
                assertTrue(s.length() < 20_000 && s.contains("elided"),
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
        final var manager = new ContextManager(cfg, new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                new CompactionCoordinator(cfg, null, new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                        providerManager(cfg), null));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 3; i++) {
            messages.add(Message.user("step " + i));
        }
        assertFalse(manager.pruneNeeded(messages),
                "a tiny conversation is far below the pruning threshold without tool schemas");
        assertTrue(manager.pruneNeeded(messages, 150_000),
                "the tool-schema tokens must count toward the pruning trigger");
        assertTrue(manager.compactNeeded(messages, 150_000),
                "the tool-schema tokens must count toward the compaction trigger");
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> Optional.empty()));
    }
}