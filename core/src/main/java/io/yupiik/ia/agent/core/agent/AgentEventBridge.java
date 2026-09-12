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
