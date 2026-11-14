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
package io.yupiik.ia.agent.github.command;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.service.TaskDiagnostics;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShowCommandTest {
    @TempDir
    Path home;

    @Test
    void privateInspectionIsExplicitAndBypassesApplicationLogging() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), ignored -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "show-test", TaskFixtures.task(Map.of("repository", "acme/repo", "number", 12, "kind", "review"))));
            final var reference = new TaskDiagnostics(home, json, BotTestConfiguration.CLOCK)
                    .capture(
                            store.transaction(state -> state.task(id).document()),
                            "preparation",
                            new IllegalArgumentException("private-original-secret"),
                            null);
            store.transaction(state -> {
                state.task(id)
                        .lastFailure(new TaskDocument.FailureDetail(
                                "preparation",
                                "IllegalArgumentException",
                                "Unable to prepare the private attempt configuration.",
                                BotTestConfiguration.CLOCK.instant().toString(),
                                1,
                                null,
                                reference,
                                true));
                return null;
            });
            final var ordinary = invoke(id);
            assertEquals(0, ordinary.exitCode(), ordinary.stderr());
            assertFalse((ordinary.stdout() + ordinary.stderr()).contains("private-original-secret"));
            assertTrue(ordinary.stderr().contains("lastFailure"));
            assertTrue(ordinary.stderr().contains("preparation"));
            final var explicit = invoke(id, "--diagnostics", "true");
            assertEquals(0, explicit.exitCode(), explicit.stderr());
            assertTrue(explicit.stdout().contains("private-original-secret"));
            assertFalse(explicit.stderr().contains("private-original-secret"));
            assertFalse(explicit.stderr().contains("lastFailure"));
        }
    }

    @Test
    void legacyTaskWithoutPrivateFilesCanBeInspected() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), ignored -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "legacy",
                    TaskFixtures.task(Map.of(
                            "repository", "acme/repo", "kind", "help", "lastError", "IllegalArgumentException"))));
            final var result = invoke(id, "--diagnostics", "true");
            assertEquals(0, result.exitCode(), result.stderr());
            assertEquals("[]", result.stdout().trim());
            assertFalse(Files.exists(home.resolve("diagnostics")));
        }
    }

    @Test
    void defaultShowDoesNotReadUnsafePrivateStorage() throws Exception {
        try (final var json = BotJsonMapper.create(List.of(), ignored -> Optional.empty())) {
            final var store = new TaskStore(BotTestConfiguration.home(home), json);
            final var id = store.transaction(state -> state.enqueue(
                    "unsafe-private", TaskFixtures.task(Map.of("repository", "acme/repo", "kind", "help"))));
            Files.createSymbolicLink(home.resolve("diagnostics"), Files.createDirectory(home.resolve("outside")));
            final var result = invoke(id);
            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stderr().contains(id));
            assertEquals("", result.stdout());
        }
    }

    private Output invoke(final String id, final String... options) throws Exception {
        final var output = Files.createTempFile(home, "show-stdout-", ".txt");
        final var error = Files.createTempFile(home, "show-stderr-", ".txt");
        final var childHome = Files.createTempDirectory(home, "cli-home-");
        final var logging = Files.writeString(
                childHome.resolve("logging.properties"),
                "handlers=java.util.logging.ConsoleHandler\n.level=INFO\njava.util.logging.ConsoleHandler.level=ALL\n");
        final var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dgithub.home=" + home,
                "-Duser.home=" + childHome,
                "-Dyca.home=" + childHome,
                "-Dyca.storageHome=" + childHome,
                "-Djava.util.logging.config.file=" + logging,
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "io.yupiik.fusion.framework.api.main.CliLauncher",
                "github",
                "bot",
                "show",
                "--task",
                id));
        command.addAll(List.of(options));
        final var process = new ProcessBuilder(command)
                .redirectOutput(output.toFile())
                .redirectError(error.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "show command timed out");
            return new Output(process.exitValue(), Files.readString(output), Files.readString(error));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "show command did not terminate");
            }
        }
    }

    private record Output(int exitCode, String stdout, String stderr) {}
}
