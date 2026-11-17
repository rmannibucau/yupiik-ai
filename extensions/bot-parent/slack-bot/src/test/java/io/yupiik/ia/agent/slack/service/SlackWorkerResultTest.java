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
package io.yupiik.ia.agent.slack.service;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.slack.context.SlackContextStorage;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SlackWorkerResultTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"Safe answer", "Quotes: \"yes\"\nBackslash: \\ and accents: \u00e9", "MAX"})
    void publishesTypedResultWithoutChangingAnswer(final String value) throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var answer = "MAX".equals(value) ? "a".repeat(fixture.configuration.maxAnswerChars()) : value;
            final var id = task("result", "U1", TaskStatus.queued, 1).id();
            final var source = "{\"summary\":" + fixture.json.toString(answer) + ",\"taskId\":\"" + id + "\"}";
            run(fixture, source);
            final var saved = fixture.store.list().getFirst();
            assertEquals(TaskStatus.done, saved.status());
            assertEquals(answer, saved.answer());
            assertEquals(1, fixture.exchanges.size());
            assertTrue(fixture.exchanges.getFirst().body().contains("\"plain_text\""));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void malformedResultFailsWithoutPublication(final String source) throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var id = task("result", "U1", TaskStatus.queued, 1).id();
            run(fixture, source.replace("TASK", id));
            final var saved = fixture.store.list().getFirst();
            assertEquals(TaskStatus.failed, saved.status());
            assertNull(saved.answer());
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    private static Stream<String> invalidResults() {
        return Stream.of(
                "null",
                "{\"taskId\":",
                "{}",
                "{\"taskId\":\"TASK\"}",
                "{\"summary\":\"answer\"}",
                "{\"taskId\":null,\"summary\":\"answer\"}",
                "{\"taskId\":\"TASK\",\"summary\":null}",
                "{\"taskId\":\"other\",\"summary\":\"answer\"}",
                "{\"taskId\":\"TASK\",\"summary\":\"\"}",
                "{\"taskId\":\"TASK\",\"summary\":\" \"}",
                "{\"taskId\":\"TASK\",\"summary\":\"" + "a".repeat(12001) + "\"}");
    }

    private void run(final SlackDomainFixture fixture, final String source) {
        fixture.add(task("result", "U1", TaskStatus.queued, 1));
        final Sandbox sandbox = new Sandbox() {
            @Override
            public int execute(
                    final Path directory, final String identity, final String prompt, final Path temporaryDirectory) {
                try {
                    Files.createDirectories(directory.resolve("output"));
                    Files.writeString(directory.resolve("output/result.json"), source);
                    return 0;
                } catch (final IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public void stop(final String identity) {}

            @Override
            public void close() {}
        };
        final var worker = new SlackWorker(
                fixture.configuration,
                fixture.executor(root),
                fixture.json,
                fixture.store,
                new SlackPromptRenderer(SlackDomainFixture.prompts(), fixture.json),
                sandbox,
                new SlackFeedback(fixture.configuration, SlackDomainFixture.prompts(), fixture.store, fixture.api),
                fixture.api,
                new HostAdmission(new HostAdmissionConfiguration("", 1, "")),
                fixture.contextConfiguration,
                fixture.actions,
                fixture.contextDispatcher,
                new SlackContextStorage());
        assertTrue(worker.runOne());
    }
}
