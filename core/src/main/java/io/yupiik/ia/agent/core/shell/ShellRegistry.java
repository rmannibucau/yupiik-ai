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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;

/**
 * Single hub between the agent event bus and the active shell. A command
 * registers its {@link ShellEnv} when it starts and unregisters it when it
 * stops; while active, agent events are forwarded to its frontend.
 * Registration and unregistration are broadcast on the event bus so stateful
 * observers (conversation transcript, stats, ...) can reset themselves -
 * this replaces the per-command sink wiring previously done through
 * a per-shell event sink.
 */
@ApplicationScoped
public class ShellRegistry {
    private final Emitter emitter;
    private volatile ShellEnv active;

    public ShellRegistry(final Emitter emitter) {
        this.emitter = emitter;
    }

    /** @return the active shell or {@code null} when no shell runs. */
    public ShellEnv active() {
        return active;
    }

    public void register(final ShellEnv env) {
        active = env;
        emitter.emit(new ShellRegistered(env));
    }

    public void unregister(final ShellEnv env) {
        if (active == env) {
            active = null;
            emitter.emit(new ShellUnregistered(env));
        }
    }

    /** Forwards agent events to the active shell frontend, no-op when idle. */
    public void onEvent(@OnEvent final AgentEvent event) {
        final var env = active;
        if (env != null) {
            env.frontend().onEvent(event);
        }
    }
}
