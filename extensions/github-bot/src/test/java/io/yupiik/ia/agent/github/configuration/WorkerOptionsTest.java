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
package io.yupiik.ia.agent.github.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.command.WorkerCommand$Options$FusionConfigurationFactory;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkerOptionsTest {
    @Test
    void sourceDefaultsPreserveAnonymousExport() {
        final var worker = options(Map.of());
        assertEquals(WorkerOptions.SourceMode.export, worker.source().mode());
        assertEquals("", worker.source().tokenEnv());
        assertEquals(1000, worker.source().maxDepth());
        assertEquals(536870912L, worker.source().maxBytes());
        assertEquals(120, worker.source().commandTimeoutSeconds());
        assertTrue(worker.agentCommenting());
        assertFalse(worker.privateSource());
    }

    @Test
    void bindsGitSourceSettingsWithoutChangingPublicationDefaults() {
        final var worker = options(Map.of(
                "worker.source.mode", "git",
                "worker.source.tokenEnv", "READ_ONLY_SOURCE_PAT",
                "worker.source.maxDepth", "2000",
                "worker.source.maxBytes", "1073741824",
                "worker.source.commandTimeoutSeconds", "300"));
        assertEquals(WorkerOptions.SourceMode.git, worker.source().mode());
        assertEquals("READ_ONLY_SOURCE_PAT", worker.source().tokenEnv());
        assertEquals(2000, worker.source().maxDepth());
        assertEquals(1073741824L, worker.source().maxBytes());
        assertEquals(300, worker.source().commandTimeoutSeconds());
        assertTrue(worker.agentCommenting());
    }

    @Test
    void exportRejectsSourceTokenEnvironmentRatherThanIgnoringIt() {
        final var failure = assertThrows(
                IllegalArgumentException.class,
                () -> options(Map.of("worker.source.tokenEnv", "READ_ONLY_SOURCE_PAT")));
        assertFalse(failure.getMessage().contains("READ_ONLY_SOURCE_PAT"));
    }

    @ParameterizedTest
    @CsvSource({
        "maxDepth, 0",
        "maxDepth, -1",
        "maxDepth, 100001",
        "maxBytes, 1048575",
        "maxBytes, 17179869185",
        "commandTimeoutSeconds, 0",
        "commandTimeoutSeconds, -1",
        "commandTimeoutSeconds, 3601"
    })
    void rejectsInvalidSourceLimits(final String setting, final String value) {
        assertThrows(IllegalArgumentException.class, () -> options(Map.of("worker.source." + setting, value)));
    }

    @ParameterizedTest
    @CsvSource({"1, 1048576, 1", "100000, 17179869184, 3600"})
    void acceptsSourceLimitBoundaries(final int depth, final long bytes, final int timeout) {
        final var source = options(Map.of(
                        "worker.source.maxDepth", Integer.toString(depth),
                        "worker.source.maxBytes", Long.toString(bytes),
                        "worker.source.commandTimeoutSeconds", Integer.toString(timeout)))
                .source();
        assertEquals(depth, source.maxDepth());
        assertEquals(bytes, source.maxBytes());
        assertEquals(timeout, source.commandTimeoutSeconds());
    }

    @Test
    void brokerIsExplicitAndDoesNotSilentlyChangePublicationPolicy() {
        final var worker = options(Map.of("worker.source.mode", "broker"));
        assertEquals(WorkerOptions.SourceMode.broker, worker.source().mode());
        assertEquals("", worker.source().tokenEnv());
        assertTrue(worker.agentCommenting()); // Worker must reject this combination, not silently change it.
        assertEquals(options(Map.of()).source().maxBytes(), worker.source().maxBytes());
        assertEquals(options(Map.of()).source().maxDepth(), worker.source().maxDepth());
    }

    @Test
    void brokerRejectsDedicatedSourcePatEvenWithStrictPublication() {
        final var failure = assertThrows(
                IllegalArgumentException.class,
                () -> options(Map.of(
                        "worker.source.mode", "broker",
                        "worker.agentCommenting", "false",
                        "worker.source.tokenEnv", "PRIVATE_READ_PAT")));
        assertFalse(failure.getMessage().contains("PRIVATE_READ_PAT"));
    }

    private WorkerOptions options(final Map<String, String> values) {
        return new WorkerCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key)))
                .get()
                .worker();
    }
}
