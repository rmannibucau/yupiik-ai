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

    @Test
    void promptSectionHonorsExplicitBudget() throws Exception {
        final var store = new MemoryStore(config(home, Map.of("yca.memory.maxChars", "8000")));
        Files.createDirectories(store.globalFile().getParent());
        Files.writeString(
                store.globalFile(),
                "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                        + "- alpha beta gamma delta epsilon zeta\n"
                        + "- a b c d e f g h i j k l\n");
        assertTrue(store.promptSection(null, 0).isEmpty(), "budget 0 must disable the section");
        assertTrue(store.promptSection(null, 5).isEmpty(), "tiny budget must disable the section");
        assertTrue(store.promptSection(null, 120).isEmpty(), "budget below the 500 floor is auto-off");
        final var small = store.promptSection(null, 700);
        // the global (only 2 bullets, ~65 chars) fits in the budget: the section keeps content
        assertTrue(small.contains("- alpha"), "budgeted section must keep content: " + small);
        assertTrue(small.length() <= 900, "budgeted section must stay small: " + small.length());
    }

    @Test
    void appendReformulationReplacesTheOldBullet() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        store.append(
                true,
                List.of(
                        "FileSessionStore and InMemorySessionStore must round-trip Message.others() on load and replace"));
        store.append(
                true,
                List.of(
                        "FileSessionStore and InMemorySessionStore must round-trip Message.others in load() and replace()"));
        final var section = store.promptSection(null);
        assertEquals(
                1,
                count(section, "FileSessionStore and InMemorySessionStore must round-trip"),
                "a reformulated near-duplicate must replace the old bullet: " + section);
        assertTrue(section.contains("round-trip Message.others in load()"), "the newest wording must win: " + section);
    }

    @Test
    void appendKeepsDistinctBulletsEvenWhenTokensOverlap() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        store.append(true, List.of("the agent prefers tabs for indentation"));
        store.append(true, List.of("the agent prefers spaces in documentation files"));
        final var section = store.promptSection(null);
        assertEquals(1, count(section, "the agent prefers tabs"), "distinct fact must be kept: " + section);
        assertEquals(1, count(section, "the agent prefers spaces"), "distinct fact must be kept: " + section);
    }

    @Test
    void withMetaRoundTripsAndIsStrippedFromPrompt() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        final var stored = MemoryStore.withMeta(5, List.of("maven", "build"), "src/main/App.java", "always run mvn -o");
        assertTrue(stored.startsWith("- 5|"), "stored bullet carries the p5 envelope: " + stored);
        store.append(true, List.of(stored));
        final var section = store.promptSection(null);
        assertTrue(section.contains("- always run mvn -o"), "fact text must be injected: " + section);
        assertFalse(section.contains("5|"), "the envelope must never leak into the prompt: " + section);
        assertFalse(section.contains("maven|build"), "topic must not leak: " + section);
        // the stored bullet must keep its metadata on read
        assertEquals(5, MemoryStore.metaOf(stored).p(), "priority must survive the round-trip");
        assertEquals("src/main/App.java", MemoryStore.metaOf(stored).path(), "path anchor must survive");
        assertEquals(List.of("maven", "build"), MemoryStore.metaOf(stored).topic(), "topic must survive");
    }

    @Test
    void plainHandWrittenLineIsCompatible() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        Files.createDirectories(store.globalFile().getParent());
        final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                + "- user prefers tabs\n"
                + "- a hand-written line with | pipe characters in it\n";
        Files.writeString(store.globalFile(), original);
        // reading must not rewrite or touch a compat line
        final var section = store.promptSection(null);
        assertTrue(section.contains("- user prefers tabs"), "plain line must be injected: " + section);
        assertTrue(
                section.contains("- a hand-written line with | pipe characters in it"),
                "pipe-heavy prose is plain content, not an envelope: " + section);
        assertEquals(original, Files.readString(store.globalFile()), "a read must not rewrite compat lines");
        // the compat line is treated as p2 plain metadata
        assertEquals(2, MemoryStore.metaOf("- user prefers tabs").p(), "a plain line defaults to p2");
    }

    @Test
    void keepSurvivesMaxLinesEvictionWhilePlainPeersDrop() throws Exception {
        final var store =
                new MemoryStore(config(home, Map.of("yca.memory.maxLines", "3", "yca.memory.maxKeepLines", "2")));
        // two keep (p5) + two plain (p2) - over the 3-line cap
        store.append(
                true,
                List.of(
                        MemoryStore.withMeta(5, null, null, "never edit generated files"),
                        MemoryStore.withMeta(5, null, null, "always run mvn -o"),
                        "an ordinary observation one",
                        "an ordinary observation two"));
        final var file = Files.readString(store.globalFile());
        assertTrue(file.contains("never edit generated files"), "keep bullets must survive eviction: " + file);
        assertTrue(file.contains("always run mvn -o"), "keep bullets must survive eviction: " + file);
        // only one plain bullet may remain (2 keep + 1 plain = 3 = maxLines)
        long plain = file.lines()
                .filter(l -> l.startsWith("- "))
                .filter(l -> MemoryStore.metaOf(l).p() == 2)
                .count();
        assertTrue(plain <= 1, "plain low-priority bullets drop first: " + file);
    }

    @Test
    void keepIsDemotedPastMaxKeepLines() throws Exception {
        final var store =
                new MemoryStore(config(home, Map.of("yca.memory.maxLines", "10", "yca.memory.maxKeepLines", "1")));
        store.append(
                true,
                List.of(
                        MemoryStore.withMeta(5, null, null, "first directive"),
                        MemoryStore.withMeta(5, null, null, "second directive")));
        final var file = Files.readString(store.globalFile());
        // with maxKeepLines=1 and two keep bullets, one keep is demoted to p4 (kept, not dropped)
        assertEquals(1, keepCount(file, 5), "exactly one keep bullet may remain: " + file);
        assertEquals(1, keepCount(file, 4), "the other keep must be demoted to p4: " + file);
        assertTrue(
                file.contains("first directive") && file.contains("second directive"),
                "neither directive is dropped: " + file);
    }

    @Test
    void pathAnchoredBulletIsBoostedForCurrentPath() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        Files.createDirectories(MemoryStore.projectFile(home).getParent());
        Files.writeString(
                MemoryStore.projectFile(home),
                "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                        + MemoryStore.withMeta(
                                2, null, "src/main/CartTotals.java", "rounding lives in CartTotals.total")
                        + "\n"
                        + MemoryStore.withMeta(2, null, null, "the sky is blue unrelated fact")
                        + "\n");
        // a prompt about rounding, with the current path present: the anchored bullet must surface
        final var section = store.promptSection("refactor rounding", 1200, home, List.of("src/main/CartTotals.java"));
        assertTrue(
                section.contains("CartTotals"),
                "the path-anchored bullet must be retrieved for the current path: " + section);
    }

    private static long keepCount(final String content, final int priority) {
        return content.lines()
                .filter(l -> l.startsWith("- "))
                .filter(l -> MemoryStore.metaOf(l).p() == priority)
                .count();
    }

    @Test
    void memoryWriteToolStoresAnExplicitKeepEnvelopeByDefault() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        final var tool = new io.yupiik.ia.agent.core.tools.builtin.MemoryWriteTool(store);
        final var result = tool.execute(Map.of("fact", "always use tabs", "scope", "global"));
        assertTrue(result.output().contains("Remembered"), "the write must report success: " + result.output());
        final var file = Files.readString(store.globalFile());
        // memory_write defaults to an explicit keep directive (p5) with no path/topic, stamped today
        final var storedKeep = file.lines()
                .filter(l -> MemoryStore.metaOf(l).p() == 5)
                .findFirst()
                .orElse(null);
        assertTrue(storedKeep != null, "a memory_write fact must be stored as a keep (p5) directive: " + file);
        assertEquals("always use tabs", MemoryStore.textOf(storedKeep), "the fact text is preserved");
    }

    @Test
    void memoryWriteToolHonorsKeepFalseAndPathTopic() throws Exception {
        final var store = new MemoryStore(config(home, Map.of()));
        final var tool = new io.yupiik.ia.agent.core.tools.builtin.MemoryWriteTool(store);
        tool.execute(Map.of(
                "fact", "the discount rule",
                "scope", "global",
                "keep", "false",
                "path", "src/main/CartTotals.java",
                "topic", "rounding discount"));
        final var file = Files.readString(store.globalFile());
        final var line = file.lines()
                .filter(l -> MemoryStore.textOf(l).equals("the discount rule"))
                .findFirst()
                .orElse(null);
        assertTrue(line != null, "the fact must be stored: " + file);
        assertEquals(2, MemoryStore.metaOf(line).p(), "keep=false stores an ordinary p2 fact");
        assertEquals("src/main/CartTotals.java", MemoryStore.metaOf(line).path(), "path anchor must be stored");
        assertEquals(List.of("rounding", "discount"), MemoryStore.metaOf(line).topic(), "topic must be stored");
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
