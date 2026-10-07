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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatClient.StructuredResult;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryExtractorTest {

    @TempDir
    Path home;

    private static CodingAgentConfiguration config(final Path home) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", home.toString());
        map.put("yca.workingDir", home.toString());
        map.put("yca.projectDir", home.toString());
        // memory enabled with the default budget so extraction actually runs
        map.put("yca.memory.enabled", "true");
        map.put("yca.memory.maxChars", "8000");
        map.put("yca.memory.maxRatio", "0.2");
        return TestConfigs.codingAgent(map);
    }

    @Test
    void structuredFailureFallsBackToSnippetAndWarnsWithContext() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        final var events = new ArrayList<AgentEvent>();
        final var chat = new ChatClient() {
            @Override
            public StructuredResult chatStructured(
                    final ChatCompletionRequest request, final String schemaName, final Map<String, Object> schema)
                    throws IOException {
                // provider rejected the JSON Schema: ChatClient falls back to a plain call and reports
                // structured=false; the extractor must then use the snippet fallback
                return new StructuredResult(
                        new ChatResult(Message.assistant("plain fallback answer", null), new Usage(1, 1, 2)), false);
            }

            @Override
            public ChatResult chat(final ChatCompletionRequest request) throws IOException, InterruptedException {
                return new ChatResult(
                        Message.assistant("- a durable fact about the project that survives the fallback\n", null),
                        new Usage(1, 1, 2));
            }
        };
        final var done = new CountDownLatch(1);
        final var extractor = new MemoryExtractor(
                chat,
                config,
                store,
                null, // mapper unused by the fallback path
                new VirtualThreadExecutor(),
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        events.add((AgentEvent) event);
                        done.countDown();
                    }
                },
                Clock.systemUTC(),
                null,
                "s-test",
                null);
        extractor.maybeExtract(List.of(Message.user("user said: the project now uses tabs")), ChatSettings.EMPTY);
        assertTrue(done.await(5, TimeUnit.SECONDS), "the extractor must emit a notice");
        // the notice fires before the snippet write: wait for the file to be updated
        final var projectFile = store.projectFile();
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(projectFile) || !Files.readString(projectFile).contains("a durable fact"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(1, events.size(), "one warning expected");
        final var notice = (AgentEvent.Notice) events.get(0);
        assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
        assertEquals("s-test", notice.sessionId());
        assertTrue(
                notice.message().contains("structured facts"),
                "the notice must name the failing phase: " + notice.message());
        assertTrue(
                notice.message().contains("rejected by the provider"),
                "the notice must explain the rejection: " + notice.message());
        assertTrue(
                notice.message().contains("saving a plain snippet instead"),
                "the notice must mention the fallback: " + notice.message());
        // the fallback snippet path must still save memory
        final var project = store.readProject();
        assertTrue(project.contains("a durable fact"), "the fallback snippet must be saved: " + project);
    }
}
