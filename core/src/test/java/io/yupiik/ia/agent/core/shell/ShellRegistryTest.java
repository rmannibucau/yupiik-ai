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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellRegistryTest {
    @Test
    void registersAndForwardsEventsToActiveFrontend() {
        final var frontend = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));
        final var env = new ShellEnv(frontend, null, new ShellSession(), new ConversationTranscript());

        assertNull(registry.active());
        registry.register(env);

        assertSame(env, registry.active());
        registry.onEvent(new AgentEvent.Status("working"));
        registry.onEvent(AgentEvent.Notice.warn("careful"));
        registry.onEvent(new AgentEvent.Error("boom"));

        assertEquals(List.of("status:working", "notice:WARN:careful", "error:boom"), frontend.calls);
    }

    @Test
    void unregisteredStopsForwarding() {
        final var frontend = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));
        final var env = new ShellEnv(frontend, null, new ShellSession(), new ConversationTranscript());

        registry.register(env);
        registry.unregister(env);
        registry.onEvent(new AgentEvent.Status("ignored"));

        assertEquals(List.of(), frontend.calls);
        assertNull(registry.active());
    }

    @Test
    void foreignUnregisterIsIgnored() {
        final var registry = new ShellRegistry(collecting(null));
        final var env = new ShellEnv(new RecordingFrontend(), null, new ShellSession(), new ConversationTranscript());
        registry.register(env);

        registry.unregister(
                new ShellEnv(new RecordingFrontend(), null, new ShellSession(), new ConversationTranscript()));

        assertSame(env, registry.active());
    }

    @Test
    void lifecycleEventsAreBroadcast() {
        final var events = new ArrayList<>();
        final var registry = new ShellRegistry(collecting(events));
        final var env = new ShellEnv(new RecordingFrontend(), null, new ShellSession(), new ConversationTranscript());

        registry.register(env);
        registry.unregister(env);

        assertEquals(List.of(new ShellRegistered(env), new ShellUnregistered(env)), events);
    }

    private static Emitter collecting(final List<Object> into) {
        return new Emitter() {
            @Override
            public <T> void emit(final T event) {
                if (into != null) {
                    into.add(event);
                }
            }
        };
    }

    private static final class RecordingFrontend implements ShellFrontend {
        final List<String> calls = new ArrayList<>();

        @Override
        public void status(final String message) {
            calls.add("status:" + message);
        }

        @Override
        public void notice(final AgentEvent.Notice.Level level, final String message) {
            calls.add("notice:" + level + ":" + message);
        }

        @Override
        public void transcript(final String markdown) {
            calls.add("transcript:" + markdown);
        }

        @Override
        public void error(final String message) {
            calls.add("error:" + message);
        }

        @Override
        public void showThinking(final boolean value) {
            calls.add("showThinking:" + value);
        }

        @Override
        public void showToolOutput(final boolean value) {
            calls.add("showToolOutput:" + value);
        }
    }
}
