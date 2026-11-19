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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryWriteToolTest {
    @TempDir
    Path storageHome;

    private CodingAgentConfiguration config() {
        return TestConfigs.codingAgent(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.workingDir", storageHome.resolve("default-project").toString()));
    }

    private ToolContext context(final String sessionId) {
        return new ToolContext(
                sessionId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                storageHome);
    }

    @Test
    void sessionRootsKeepProjectWritesIsolatedAndGlobalWritesShared() {
        final var store = new MemoryStore(config());
        final var tool = new MemoryWriteTool(store);
        final var alpha = storageHome.resolve("alpha");
        final var beta = storageHome.resolve("beta");
        assertTrue(tool.execute(Map.of("fact", "alpha decision"), ToolContext.empty(alpha))
                .success());
        assertTrue(tool.execute(Map.of("fact", "beta deployment"), ToolContext.empty(beta))
                .success());
        assertTrue(tool.execute(Map.of("fact", "shared preference", "scope", "global"), ToolContext.empty(alpha))
                .success());
        assertTrue(tool.execute(Map.of("fact", "another preference", "scope", "global"), ToolContext.empty(beta))
                .success());
        assertTrue(tool.execute(Map.of("fact", "legacy default"), ToolContext.empty(storageHome))
                .success());
        assertThrows(NullPointerException.class, () -> tool.execute(Map.of("fact", "null context default"), null));
        assertTrue(store.readProject(alpha).contains("alpha decision"));
        assertFalse(store.readProject(alpha).contains("beta deployment"));
        assertTrue(store.readProject(beta).contains("beta deployment"));
        assertFalse(store.readProject(beta).contains("alpha decision"));
        assertTrue(store.readGlobal().contains("shared preference"));
        assertTrue(store.readGlobal().contains("another preference"));
        assertTrue(store.readProject(storageHome).contains("legacy default"));
        assertFalse(store.readProject(storageHome).contains("null context default"));
        assertFalse(store.readProject(storageHome).contains("alpha decision"));
        assertFalse(store.readProject(storageHome).contains("beta deployment"));
    }

    @Test
    void writesWhenEnabled() {
        final var store = new MemoryStore(config());
        final var policy = new MemoryProductionPolicy();
        final var tool = new MemoryWriteTool(store, policy);
        final var result = tool.execute(Map.of("fact", "remember tabs"), context("s1"));
        assertTrue(result.success(), "an enabled session stores the fact: " + result.output());
        assertTrue(store.readProject(storageHome).contains("remember tabs"), "the fact must be persisted when enabled");
        assertTrue(store.readGlobal().isBlank(), "default project scope writes to project memory only");
    }

    @Test
    void dropsTheFactWhenDisabledButDoesNotError() {
        final var store = new MemoryStore(config());
        final var policy = new MemoryProductionPolicy();
        final var tool = new MemoryWriteTool(store, policy);
        final var result = tool.execute(Map.of("fact", "forgotten"), context("s1"));
        assertTrue(result.success(), "a fresh session stores the fact: " + result.output());

        policy.set("s1", false);
        final var disabled = tool.execute(Map.of("fact", "must not land"), context("s1"));
        assertTrue(disabled.success(), "a disabled session still acknowledges the call (no error)");
        assertFalse(
                store.readProject(storageHome).contains("must not land"),
                "a disabled session must not persist the fact");
        assertTrue(store.readProject(storageHome).contains("forgotten"), "facts from before disabling stay");
    }

    @Test
    void standaloneContextAndOtherSessionsStayEnabled() {
        final var store = new MemoryStore(config());
        final var policy = new MemoryProductionPolicy();
        final var tool = new MemoryWriteTool(store, policy);
        policy.set("s1", false);

        // a standalone/empty context (no session id) must still produce
        final var standalone = tool.execute(Map.of("fact", "standalone fact"), ToolContext.empty(storageHome));
        assertTrue(standalone.success(), "a standalone context still produces: " + standalone.output());
        assertTrue(store.readProject(storageHome).contains("standalone fact"));

        // another session (untouched) is unaffected
        final var other = tool.execute(Map.of("fact", "other session fact"), context("s2"));
        assertTrue(other.success(), "another session still produces: " + other.output());
        assertTrue(store.readProject(storageHome).contains("other session fact"));
    }
}
