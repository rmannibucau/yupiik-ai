package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CompactionConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderListConfiguration;
import io.yupiik.ia.agent.core.configuration.PruningConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;

class ContextManagerTest {

    private static CodingAgentConfiguration config(final int keepRecent) {
        final var provider = new ProviderConfiguration("default", "https://api.openai.com/v1", "", "m", null, null, 128000, 120, "");
        return new CodingAgentConfiguration("x", ".",
                new ProviderListConfiguration(List.of(provider), "", null, null, null, null, null, 128000, 120),
                new ModelConfiguration("", ""),
                new LlmConfiguration(3, 500L, 0, 4, true),
                new CompactionConfiguration(true, 80),
                new PruningConfiguration(true, 65, keepRecent, 80),
                null, null, null, null, null, null, null, null);
    }

    @Test
    void compactForOverflowIsNoOpOnSmallConversations() {
        final var cfg = config(6);
        final var manager = new ContextManager(cfg, null, new UsageTracker(providerManager(cfg)), providerManager(cfg));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 6; i++) {
            messages.add(Message.user("step " + i));
        }
        assertSame(messages, manager.compactForOverflow(messages),
                "a conversation that already fits the keep window must stay untouched");
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> Optional.empty()));
    }
}