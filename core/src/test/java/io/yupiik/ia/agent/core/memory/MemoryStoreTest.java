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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {

    @TempDir
    Path home;

    @Test
    void promptSectionFiltersHeaderAndDeduplicates() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        Files.createDirectories(store.globalFile().getParent());
        // a memory file as written by the store itself: header + one duplicated bullet
        Files.writeString(
                store.globalFile(),
                "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                        + "- fact one\n"
                        + "- fact one\n"
                        + "- fact two\n");
        final var section = store.promptSection(null);
        assertFalse(section.contains("Durable facts"), "the file header must not leak into the prompt: " + section);
        assertEquals(1, count(section, "- fact one"), "duplicated bullets must be deduplicated: " + section);
        assertEquals(1, count(section, "- fact two"), "distinct bullets must be kept: " + section);
    }

    @Test
    void versionIsStableThenBumpsOnAppend() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        final var v1 = store.version();
        assertEquals(v1, store.version(), "version must be stable while memory is untouched");
        store.append(true, List.of("a new durable fact"));
        final var v2 = store.version();
        assertTrue(v2 != v1, "a write must bump the content version");
        assertEquals(v2, store.version(), "the new version must be stable too");
    }

    @Test
    void appendDeduplicatesIdenticalFacts() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        final var first = store.append(true, List.of("the same fact"));
        final var second = store.append(true, List.of("the same fact"));
        assertEquals(1, first.size());
        assertTrue(second.isEmpty(), "a repeated append of the same fact must be a no-op");
        assertEquals(1, count(store.promptSection(null), "- the same fact"));
    }

    private static int count(final String haystack, final String needle) {
        var out = 0;
        var from = 0;
        while (true) {
            final var i = haystack.indexOf(needle, from);
            if (i < 0) {
                return out;
            }
            out++;
            from = i + needle.length();
        }
    }

    private static CodingAgentConfiguration config(final Path storageHome, final Map<String, String> overrides) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", storageHome.toString());
        map.put("yca.workingDir", ".");
        map.putAll(overrides);
        return TestConfigs.codingAgent(map);
    }
}
