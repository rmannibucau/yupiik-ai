package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CompactionConfiguration;
import io.yupiik.ia.agent.core.configuration.LlmConfiguration;
import io.yupiik.ia.agent.core.configuration.ModelConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.PruningConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class ContextManagerTest {

    private static CodingAgentConfiguration config(final int keepRecent) {
        return new CodingAgentConfiguration("x", ".",
                new ProviderConfiguration("https://api.openai.com/v1", "", "m", null, null, 128000, 120),
                new ModelConfiguration("", ""),
                new LlmConfiguration(3, 500L, 0, 4, true),
                new CompactionConfiguration(true, 80),
                new PruningConfiguration(true, 65, keepRecent, 80),
                null, null, null, null, null, null, null, null);
    }

    @Test
    void compactForOverflowIsNoOpOnSmallConversations() {
        final var manager = new ContextManager(config(6), null, new UsageTracker(config(6)));
        final var messages = new ArrayList<Message>();
        messages.add(Message.system("system"));
        for (var i = 0; i < 6; i++) {
            messages.add(Message.user("step " + i));
        }
        assertSame(messages, manager.compactForOverflow(messages),
                "a conversation that already fits the keep window must stay untouched");
    }
}