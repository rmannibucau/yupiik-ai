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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.agent.AgentLoop;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A shell registering (TUI tab creation, web session attach, {@code /resume}) must trigger a quota
 * refresh so the sidebar gauge shows usage before the first turn ran even when no provider switch
 * happens; shells without a runtime must be ignored (no NPE, no push).
 */
class ShellQuotaInitializerTest {
    @Test
    void registeredShellWithRuntimeRefreshesQuota() {
        final var refreshed = new AtomicInteger();
        final var loop = new AgentLoop() {
            @Override
            public void refreshUsage() {
                refreshed.incrementAndGet();
            }
        };
        final var runtime = new SessionRuntime(loop, null, null, null, null, null, null, null);
        final var session = new ShellSession().sessionId("t1").runtime(runtime);
        final var env = new ShellEnv(null, null, session, new ConversationTranscript());

        new ShellQuotaInitializer().onShellRegistered(new ShellRegistered(env));

        assertEquals(1, refreshed.get(), "the registered shell's runtime must push its usage");
    }

    @Test
    void registeredShellWithoutRuntimeIsIgnored() {
        final var env = new ShellEnv(null, null, new ShellSession().sessionId("t2"), new ConversationTranscript());

        assertDoesNotThrow(() -> new ShellQuotaInitializer().onShellRegistered(new ShellRegistered(env)));
    }
}
