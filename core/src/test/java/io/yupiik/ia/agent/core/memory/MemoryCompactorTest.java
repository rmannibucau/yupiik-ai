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

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
        map.put("yca.model.compact", "compact-model");
        return TestConfigs.codingAgent(map);
    }

    /** Returns a fixed shortened memory, as a compact model would. */
    private static final class FakeChatClient extends ChatClient {
        @Override
        public ChatResult chat(final ChatCompletionRequest request) {
            return new ChatResult(
                    Message.assistant(
                            "- the agent prefers tabs for indentation\n- in /tmp/bench one-off observation that is stale now\n",
                            null),
                    new Usage(0, 0, 0));
        }
    }
}
