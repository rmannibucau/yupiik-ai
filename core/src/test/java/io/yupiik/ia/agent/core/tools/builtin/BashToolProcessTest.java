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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the bash tree-kill: a command that spawns a foreground child and waits
 * ({@code sh -c 'sleep 30 & echo $! > pid; wait'}) must not leave the child running after the
 * bash tool times out or is interrupted. Uses pid-file polling and a short timeout so the test is
 * fast and never sleeps on the real 30s.
 */
class BashToolProcessTest {

    @Test
    void timeoutKillsTheWholeTree(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(200);
        final var bash = new BashTool(NOOP, service);
        final var pidFile = tmp.resolve("child.pid");

        final var command = "sh -c 'sleep 30 & echo $! > " + pidFile + "; wait'";
        final var result = bash.execute(Map.of("command", command, "timeout_seconds", "1"), context());
        assertFalse(result.success(), "the command must time out: " + result.output());

        final var childPid = awaitPid(pidFile);
        assertNotNull(childPid, "the child must have been spawned before the timeout");
        awaitGone(childPid);
        assertTrue(service.trackedCount() == 0, "no process may stay tracked after the timeout");
    }

    private static io.yupiik.ia.agent.core.tools.ToolContext context() {
        return io.yupiik.ia.agent.core.tools.ToolContext.empty();
    }

    private static Long awaitPid(final Path pidFile) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final var raw = Files.readString(pidFile).trim();
                if (!raw.isEmpty()) {
                    return Long.parseLong(raw);
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static void awaitGone(final long pid) throws InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("bash child " + pid + " survived the tree kill");
    }

    private static final Emitter NOOP = new Emitter() {
        @Override
        public <T> void emit(final T event) {
            // no-op
        }
    };
}
