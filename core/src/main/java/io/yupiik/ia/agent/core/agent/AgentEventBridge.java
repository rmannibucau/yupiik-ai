package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;

import java.util.function.Consumer;

/**
 * Forwards streamed {@link AgentEvent}s to a {@link Consumer} sink. Frontends
 * (the TamboUI {@code tui} and the plain-terminal {@code cli}) set a sink
 * around a call to {@link AgentLoop#run} and clear it afterwards; events are
 * delivered on the fusion event bus because {@link AgentLoop} emits through
 * the container {@code Emitter}.
 */
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