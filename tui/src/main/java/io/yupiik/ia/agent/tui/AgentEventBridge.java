package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;

import java.util.function.Consumer;

@ApplicationScoped
public class AgentEventBridge {
    private volatile Consumer<AgentEvent> sink;

    public void sink(final Consumer<AgentEvent> sink) {
        this.sink = sink;
    }

    public void onEvent(@OnEvent final AgentEvent event) {
        final var consumer = sink;
        if (consumer != null) {
            consumer.accept(event);
        }
    }
}
