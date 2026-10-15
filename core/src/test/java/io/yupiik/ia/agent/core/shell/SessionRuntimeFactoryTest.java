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

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the stale session-project-marker fallback: {@link
 * SessionRuntimeFactory#resolveSessionWorkdir} must never hand a dead directory (project moved or
 * deleted since the session was recorded) to the tools, but fall back to the configured working
 * directory so the session still gets a live cwd.
 */
class SessionRuntimeFactoryTest {

    @Test
    void staleRecordedMarkerFallsBackToTheConfiguredWorkdir(@TempDir final Path tmp) throws Exception {
        final var configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        final var missingMarker =
                tmp.resolve("gone-project").toAbsolutePath().normalize().toString();

        final var config = TestConfigs.codingAgent(Map.of("yca.workingDir", configDir.toString()));
        final var store = storeRecording(missingMarker);
        final var factory = factory(config, store);

        assertEquals(
                configDir.toAbsolutePath().normalize(),
                factory.resolveSessionWorkdir("s1"),
                "a recorded marker that no longer exists must fall back to the configured working dir");
    }

    @Test
    void liveRecordedMarkerIsKept(@TempDir final Path tmp) throws Exception {
        final var live = tmp.resolve("live");
        Files.createDirectories(live);
        final var configDir = tmp.resolve("config");
        Files.createDirectories(configDir);

        final var config = TestConfigs.codingAgent(Map.of("yca.workingDir", configDir.toString()));
        final var store = storeRecording(live.toAbsolutePath().normalize().toString());
        final var factory = factory(config, store);

        assertEquals(
                live.toAbsolutePath().normalize(),
                factory.resolveSessionWorkdir("s1"),
                "a recorded marker that still exists must keep precedence over the configured dir");
    }

    @Test
    void noRecordFallsBackToTheConfiguredWorkdir(@TempDir final Path tmp) throws Exception {
        final var configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        final var config = TestConfigs.codingAgent(Map.of("yca.workingDir", configDir.toString()));
        final var factory = factory(config, storeRecording(""));

        assertEquals(
                configDir.toAbsolutePath().normalize(),
                factory.resolveSessionWorkdir("s1"),
                "without a marker the configured working dir must be used");
    }

    @Test
    void noStoreNorConfigReturnsNull() {
        final var factory = new SessionRuntimeFactory(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        assertNull(factory.resolveSessionWorkdir("s1"), "with no session store nothing can be resolved");
    }

    private static SessionStore storeRecording(final String marker) {
        return new SessionStore() {
            @Override
            public String projectDirOf(final String id) {
                return marker;
            }
        };
    }

    private static SessionRuntimeFactory factory(final CodingAgentConfiguration config, final SessionStore store) {
        return new SessionRuntimeFactory(
                null, null, null, config, null, store, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }
}
