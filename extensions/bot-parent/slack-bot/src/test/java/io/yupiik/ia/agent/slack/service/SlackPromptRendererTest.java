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

import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackPromptRendererTest {
    @TempDir
    Path root;

    @Test
    void untrustedHandlebarsAndMalformedTemplatesRemainLiteralJsonData() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var renderer = new SlackPromptRenderer(
                    new SlackPrompts(
                            "policy", "{{{request}}}\n{{{context}}}", "output", "Answer", "Cancel", "Question"),
                    fixture.json);
            final var hostile = "{{#each missing}} {{>secret}} {{{policy}}} {{broken \"\n";
            final var current = request(task("current", "U1", "queued", 100), hostile);
            final var previous = request(task("old", "U1", "done", 1), hostile).publication(hostile, "2.1", "done", 2);
            final var rendered = renderer.render(current, List.of(previous));
            final var lines = rendered.split("\n");
            assertEquals(hostile, fixture.json.fromString(String.class, lines[0]));
            final var history = (List<?>) fixture.json.fromString(Object.class, lines[1]);
            assertEquals(List.of(Map.of("request", hostile, "answer", hostile)), history);
            assertTrue(rendered.contains(current.id()));
            assertTrue(rendered.contains("exactly taskId and summary"));
        }
    }

    @Test
    void historyUsesOnlyPriorDoneTasksInSameOwnerConversationAndAtMostFive() throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var renderer = new SlackPromptRenderer(
                    new SlackPrompts("policy", "{{{context}}}", "output", "Answer", "Cancel", "Question"),
                    fixture.json);
            final var history = new ArrayList<SlackTask>();
            for (int i = 1; i <= 7; i++) {
                history.add(task("old" + i, "U1", "done", i).publication("answer" + i, "2.1", "done", i));
            }
            history.add(task("other", "U2", "done", 8).publication("PRIVATE", "2.1", "done", 8));
            history.add(task("failed", "U1", "failed", 8).publication("FAILED", null, "failed", 8));
            history.add(task("future", "U1", "done", 200).publication("FUTURE", "2.1", "done", 200));
            final var rendered = renderer.render(task("current", "U1", "queued", 100), history);
            final var previous = (List<?>) fixture.json.fromString(
                    Object.class, rendered.lines().findFirst().orElseThrow());
            assertEquals(5, previous.size());
            assertEquals("answer7", ((Map<?, ?>) previous.getFirst()).get("answer"));
            assertEquals("answer3", ((Map<?, ?>) previous.getLast()).get("answer"));
            assertFalse(rendered.contains("PRIVATE"));
            assertFalse(rendered.contains("FAILED"));
            assertFalse(rendered.contains("FUTURE"));
        }
    }

    private SlackTask request(final SlackTask task, final String text) {
        return new SlackTask(
                task.id(),
                task.workspace(),
                task.channel(),
                task.thread(),
                task.requester(),
                text,
                task.conversation(),
                task.status(),
                task.created(),
                task.updated(),
                task.attempt(),
                task.attemptPath(),
                task.runtimeId(),
                task.fingerprint(),
                task.answer(),
                task.messageTs(),
                task.question());
    }
}
