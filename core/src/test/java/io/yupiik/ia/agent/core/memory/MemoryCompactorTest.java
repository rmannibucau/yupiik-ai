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
package io.yupiik.ia.agent.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryCompactorTest {

    @TempDir
    Path home;

    @Test
    void compactRewritesAndBacksUp() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        Files.createDirectories(store.projectFile().getParent());
        final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                + "- the agent prefers tabs for indentation\n"
                + "- the agent prefers tabs for indentation (reformulated)\n"
                + "- in /tmp/bench one-off observation that is stale now\n";
        Files.writeString(store.projectFile(), original);

        final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
        final var result = compactor.compactNow();
        assertTrue(result.startsWith("memory compacted"), result);

        final var kept = Files.readString(store.projectFile());
        assertTrue(kept.contains("the agent prefers tabs"), kept);
        assertTrue(Files.exists(store.projectFile().resolveSibling("MEMORY.md.bak")), "backup must exist");
        final var baked = Files.readString(store.projectFile().resolveSibling("MEMORY.md.bak"));
        assertEquals(original, baked, "backup must hold the pre-rewrite content");
        assertNotNull(compactor.state(), "compaction state must be recorded");
    }

    @Test
    void compactDoesNotRunBelowThreshold() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        Files.createDirectories(store.projectFile().getParent());
        Files.writeString(
                store.projectFile(),
                "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n- small\n");

        final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
        assertNull(compactor.maybeCompact(), "below threshold nothing must run");
        assertNull(compactor.state(), "no state must be recorded");
    }

    @Test
    void rewriteFailureEmitsAWarningNotice() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        Files.createDirectories(store.projectFile().getParent());
        final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                + "- the agent prefers tabs for indentation\n"
                + "- another durable fact about the project that pushes the size over the threshold\n";
        Files.writeString(store.projectFile(), original);
        final List<AgentEvent> events = new ArrayList<>();
        final var sessionId = "s-test";
        final var compactor = new MemoryCompactor(
                new FakeChatClient(true),
                config,
                store,
                null,
                null,
                new io.yupiik.fusion.framework.api.event.Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        if (event instanceof AgentEvent agentEvent) {
                            events.add(agentEvent);
                        }
                    }
                },
                sessionId);
        final var result = compactor.compactNow();

        assertTrue(
                result.contains("left untouched"), "the rewrite failure must surface as a no-output result: " + result);
        assertEquals(1, events.size(), "exactly one warning notice expected");
        assertTrue(events.get(0) instanceof AgentEvent.Notice, "a Notice event expected");
        final var notice = (AgentEvent.Notice) events.get(0);
        assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
        assertEquals(sessionId, notice.sessionId());
        assertTrue(
                notice.message().contains("memory compaction failed"),
                "the notice must carry the failure context: " + notice.message());
        assertTrue(
                notice.message().contains("json was malformed"),
                "the notice must keep the technical detail: " + notice.message());
        assertTrue(
                notice.message().contains("/memory compact"),
                "the notice must include the recovery hint: " + notice.message());
        assertNotNull(notice.cause(), "the notice must carry the failing IOException for the Alt+E stack");
        assertTrue(
                notice.cause().getMessage().contains("json was malformed"),
                "the cause must be the thrown IOException: " + notice.cause());
    }

    @Test
    void compactDiscardsReductionBelowTwentyPercent() throws Exception {
        final var config = config(home);
        final var store = new MemoryStore(config);
        Files.createDirectories(store.projectFile().getParent());
        // a file whose rewrite (same content) would not shrink by 20%
        final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                + "- fact number one about the project\n"
                + "- fact number two about the project\n";
        Files.writeString(store.projectFile(), original);

        final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
        final var result = compactor.compactNow();
        assertTrue(result.contains("kept the file"), result);
        assertEquals(original, Files.readString(store.projectFile()), "the file must be untouched");
    }

    private static CodingAgentConfiguration config(final Path home) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", home.toString());
        map.put("yca.workingDir", home.toString());
        map.put("yca.projectDir", home.toString());
        map.put("yca.model.compact.name", "compact-model");
        return TestConfigs.codingAgent(map);
    }

    /** Returns a fixed shortened memory, as a compact model would. */
    private static final class FakeChatClient extends ChatClient {
        private final boolean fail;

        private FakeChatClient() {
            this(false);
        }

        private FakeChatClient(final boolean fail) {
            this.fail = fail;
        }

        @Override
        public ChatResult chat(final ChatCompletionRequest request) throws IOException {
            if (fail) {
                throw new IOException(
                        "LLM request failed (400): {\"error\":{\"type\":\"invalid_request_error\",\"message\":\"json was malformed\"}}");
            }
            return new ChatResult(
                    Message.assistant(
                            "- the agent prefers tabs for indentation\n- in /tmp/bench one-off observation that is stale now\n",
                            null),
                    new Usage(0, 0, 0));
        }
    }
}
