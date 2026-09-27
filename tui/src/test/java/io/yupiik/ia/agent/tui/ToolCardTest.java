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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCardTest {
    private final DiffRenderer diffRenderer = new DiffRenderer();

    @Test
    void parsesSuccessCard() {
        final var card = ToolCard.parse("*streaming `bash`: ls -la* (123 ms)\n\n```text\nfile1\nfile2\n```");
        assertEquals("bash", card.name());
        assertEquals("ls -la", card.args());
        assertFalse(card.failed());
        assertEquals(123, card.elapsedMs());
        assertEquals("file1\nfile2", card.output());
    }

    @Test
    void parsesOrdinalWithDuration() {
        final var card = ToolCard.parse("*streaming `bash`: ls* (#12, 123 ms)\n\n```text\nout\n```");
        assertEquals(12, card.ordinal());
        assertEquals(123, card.elapsedMs());
        assertEquals("ls", card.args());
    }

    @Test
    void parsesFailedCardWithOrdinal() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (#7, 42 ms)");
        assertTrue(card.failed());
        assertEquals(7, card.ordinal());
        assertEquals(42, card.elapsedMs());
    }

    @Test
    void legacyHeaderHasNoOrdinal() {
        final var card = ToolCard.parse("*streaming `bash`: ls* (123 ms)");
        assertEquals(-1, card.ordinal());
    }

    @Test
    void parsesStarlessDuration() {
        final var card = ToolCard.parse("*streaming `git diff`: --stat* (5 ms)\n\n```text\n 1 file changed\n```");
        assertEquals("git diff", card.name());
        assertEquals("--stat", card.args());
        assertEquals(5, card.elapsedMs());
    }

    @Test
    void parsesFailedCard() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (42 ms)");
        assertEquals("bash", card.name());
        assertTrue(card.failed());
        assertEquals("boom", card.args());
        assertEquals(42, card.elapsedMs());
        assertEquals("", card.output());
    }

    @Test
    void failedWithoutDuration() {
        final var card = ToolCard.parse("> tool `read` failed: nope");
        assertTrue(card.failed());
        assertEquals("nope", card.args());
        assertEquals(0, card.elapsedMs());
    }

    @Test
    void nameOnlyWithoutArgs() {
        final var card = ToolCard.parse("*streaming `think`* (1 ms)\n\n```text\nthought\n```");
        assertEquals("think", card.name());
        assertEquals("", card.args());
        assertEquals(1, card.elapsedMs());
    }

    @Test
    void parsesRunningCard() {
        final var card = ToolCard.parse("`| running tail -f log`\n\n```text\nstreamed line\n```");
        assertTrue(card.running());
        assertEquals("tail -f log", card.name());
        assertEquals("", card.args());
        assertFalse(card.failed());
        assertEquals(0, card.elapsedMs());
        assertEquals("streamed line", card.output());
    }

    @Test
    void parsesRunningCardWithoutCommand() {
        final var card = ToolCard.parse("`* running read_file`");
        assertTrue(card.running());
        assertEquals("read_file", card.name());
        assertEquals("", card.output());
    }

    @Test
    void detectsDiff() {
        final var card = ToolCard.parse(
                "*streaming `apply_patch`* (9 ms)\n\n```text\n--- a/File.java\n+++ b/File.java\n@@ -1,3 +1,4 @@\n-old\n+new\n context\n```");
        assertTrue(card.isDiff(diffRenderer));
    }

    @Test
    void doesNotDetectPlainOutputAsDiff() {
        final var card = ToolCard.parse("*streaming `bash`* (7 ms)\n\n```text\nhello world\nfoo bar\n```");
        assertFalse(card.isDiff(diffRenderer));
    }

    @Test
    void keyCommandExtractsSignificantCommand() {
        final var card = ToolCard.parse("*streaming `bash`: cd /foo && ls -la* (#31, 3 ms)");
        assertEquals("ls -la", card.keyCommand());
    }

    @Test
    void keyCommandKeepsPlainCommand() {
        final var card = ToolCard.parse("*streaming `bash`: mvn -q test* (#1, 5 ms)");
        assertEquals("mvn -q test", card.keyCommand());
    }

    @Test
    void keyCommandTruncatesLongCommands() {
        final var card = ToolCard.parse("*streaming `bash`: echo " + "x".repeat(120) + "* (#2, 5 ms)");
        final var key = card.keyCommand();
        assertEquals(48 + "...".length(), key.length());
        assertTrue(key.endsWith("..."));
    }

    @Test
    void keyCommandCondensesRunningCommand() {
        final var card = ToolCard.parse("`| running cd /tmp && tail -f log`");
        assertTrue(card.running());
        assertEquals("tail -f log", card.keyCommand());
    }

    @Test
    void keyCommandEmptyWithoutArgs() {
        final var card = ToolCard.parse("*streaming `think`* (1 ms)");
        assertEquals("", card.keyCommand());
    }

    @Test
    void keyCommandShowsFailedReason() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (#7, 42 ms)");
        assertEquals("boom", card.keyCommand());
    }
}
