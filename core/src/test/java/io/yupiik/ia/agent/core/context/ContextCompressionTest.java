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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CompressConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextCompressionTest {

    private static CompressConfiguration config() {
        return new CompressConfiguration(
                true, "50000", "100000", 15, 5, true, false, false, List.of("my_tool"), false, 4, true, true, 4);
    }

    private static CompressConfiguration protectedConfig() {
        return new CompressConfiguration(
                true, "50000", "100000", 15, 5, true, true, true, List.of("my_tool"), true, 1, true, true, 4);
    }

    private static List<Message> simpleConversation() {
        return List.of(
                Message.system("system"),
                Message.user("first task"),
                Message.assistant("done", List.of()),
                Message.user("next task"),
                Message.assistant("also done", List.of()));
    }

    private static List<Message> toolConversation() {
        return List.of(
                Message.system("system"),
                Message.user("task one"),
                Message.assistant("calling", List.of()),
                Message.toolResult("call-1", "OK from my_tool", "my_tool"),
                Message.user("task two"),
                Message.assistant("done", List.of()));
    }

    private static List<Message> assignedRefs(final ContextCompression compression, final List<Message> messages) {
        compression.publish(messages);
        return compression.renderRefs(messages);
    }

    @Test
    void refsAreStableForAMessageIdentity() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var first = compression.refOf(messages.get(1));
        final var second = compression.refOf(messages.get(2));
        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(first, compression.refOf(messages.get(1)), "refs must never be reassigned");
        assertSame(messages.get(2), compression.messageOf(second));
    }

    @Test
    void renderRefsTagsOnlySpeakableMessages() {
        final var compression = new ContextCompression(config());
        final var tagged = assignedRefs(compression, simpleConversation());
        assertFalse(tagged.get(0).content().toString().contains("@"), "the system prompt is never tagged");
        assertTrue(
                tagged.get(1).content().toString().endsWith("\n@1@"),
                tagged.get(1).content().toString());
        assertTrue(
                tagged.get(2).content().toString().endsWith("\n@2@"),
                tagged.get(2).content().toString());
    }

    @Test
    void stripEchoedRefsRemovesCompactSeamsFromModelOutput() {
        assertEquals("done\ncontinue", ContextCompression.stripEchoedRefs("done\n@90@\ncontinue"));
        assertEquals("just a ref", ContextCompression.stripEchoedRefs("just a ref @7@"));
        assertEquals("nested sum", ContextCompression.stripEchoedRefs("nested @b2@ sum"));
        assertEquals("blocked turn", ContextCompression.stripEchoedRefs("blocked @blocked@ turn"));
        assertEquals("prioritized", ContextCompression.stripEchoedRefs("prioritized @3@ [high]"));
        assertEquals("no refs here", ContextCompression.stripEchoedRefs("no refs here"));
        assertEquals("keeps at-word mention @user", ContextCompression.stripEchoedRefs("keeps at-word mention @user"));
        assertNull(ContextCompression.stripEchoedRefs(null));
    }

    @Test
    void compressReplacesTheSpanInRequestsOnly() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result =
                compression.compress("first task", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertTrue(result.success(), result.message());
        assertEquals("b1", result.blockId());
        assertEquals(1, compression.blocks().size());
        assertEquals(
                ContextCompression.Status.ACTIVE, compression.blocks().get(0).status());
        final var rewritten = compression.rewrite(messages);
        assertEquals(4, rewritten.size(), "two messages collapse into one block");
        assertTrue(
                rewritten.get(1).content().toString().startsWith("[Compressed conversation section @b1@]"),
                rewritten.get(1).content().toString());
        assertEquals(5, messages.size(), "the transcript itself must never be mutated");
        assertTrue(compression.savedTokens(messages) > 0);
        assertEquals(
                Math.max(0, 1_000 - compression.savedTokens(messages)), compression.effectiveTokens(messages, 1_000));
    }

    @Test
    void rangeWithUnreachableUserStartIsSkippedWithNudge() {
        // a range that cannot reach a user start (here an assistant ref with no later user before end)
        // is dropped and the model is nudged, never hard-rejected
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress("bad", List.of(new ContextCompression.Range("2", "2", "nope")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(compression.blocks().isEmpty(), "the folded range must not create a block");
    }

    @Test
    void startSnapsForwardToNextUser() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation(); // refs: 1=user t1, 2=assistant, 3=tool, 4=user t2, 5=assistant
        assignedRefs(compression, messages);
        final var result = compression.compress("t", List.of(new ContextCompression.Range("2", "5", "t2 chap")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("adjusted"), result.message());
        assertTrue(result.message().contains("start ref"), result.message());
        assertEquals(1, compression.blocks().size());
        assertEquals(4, compression.blocks().get(0).startRef());
        assertEquals(5, compression.blocks().get(0).endRef());
    }

    @Test
    void endSnapsBackwardToTurnBoundary() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation();
        assignedRefs(compression, messages);
        // end ref 4 (user t2) is not a turn boundary (an assistant reply follows) - snap it back to the
        // preceding tool result so the tool is never separated from its caller
        final var result = compression.compress("t", List.of(new ContextCompression.Range("1", "4", "t1 chap")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("adjusted"), result.message());
        assertTrue(result.message().contains("turn boundary"), result.message());
        assertEquals(1, compression.blocks().size());
        assertEquals(1, compression.blocks().get(0).startRef());
        assertEquals(3, compression.blocks().get(0).endRef());
    }

    @Test
    void collapsingRangeIsSkippedWithNudge() {
        final var compression = new ContextCompression(config());
        final var messages = toolConversation();
        assignedRefs(compression, messages);
        // start 2 (assistant) snaps forward to ref 4 (user) and end 4 snaps backward to the same ref:
        // start >= end after snapping, so the range collapses and is skipped
        final var result = compression.compress("t", List.of(new ContextCompression.Range("2", "4", "nope")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(compression.blocks().isEmpty(), "the collapsing range must not create a block");
    }

    @Test
    void rangeWithNoReachableTurnBoundaryIsSkippedWithNudge() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("task"),
                Message.assistant("calling", List.of()),
                Message.toolResult("call-1", "OK", "my_tool"));
        assignedRefs(compression, messages);
        // end ref 2 (assistant, followed by a tool result) cannot be snapped back to any turn boundary
        // within the range, so the whole entry is dropped with a nudge
        final var result = compression.compress("bad", List.of(new ContextCompression.Range("1", "2", "nope")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertTrue(compression.blocks().isEmpty());
    }

    @Test
    void rangesInOneCallMustNotOverlapEachOther() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "bad",
                List.of(
                        new ContextCompression.Range("1", "4", "whole"),
                        new ContextCompression.Range("3", "4", "tail")));
        assertFalse(result.success());
        assertTrue(result.message().contains("must not overlap"), result.message());
    }

    @Test
    void overlappingActiveBlockRequiresItsPlaceholder() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first done")))
                .success());
        final var withoutRef =
                compression.compress("greedy", List.of(new ContextCompression.Range("1", "4", "no placeholder")));
        assertFalse(withoutRef.success());
        assertTrue(withoutRef.message().contains("overlaps active block"), withoutRef.message());
    }

    @Test
    void nestingACoveredBlockViaPlaceholderSupersedesIt() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first task completed")))
                .success());
        final var nested = compression.compress(
                "whole", List.of(new ContextCompression.Range("1", "4", "@b1@ plus the second user turn")));
        assertTrue(nested.success(), nested.message());
        assertEquals("b2", nested.blockId());
        final var b1 = compression.blocks().stream()
                .filter(block -> block.id().equals("b1"))
                .findFirst()
                .orElseThrow();
        assertEquals(ContextCompression.Status.SUPERSEDED, b1.status());
        assertEquals("b2", b1.supersededBy());
        final var b2 = compression.blocks().stream()
                .filter(block -> block.id().equals("b2"))
                .findFirst()
                .orElseThrow();
        assertTrue(b2.summary().startsWith("first task completed"), b2.summary());
        assertTrue(b2.consumedBlockIds().contains("b1"), b2.consumedBlockIds().toString());
        assertEquals(2, compression.rewrite(messages).size(), "only the outer block remains applied");
    }

    @Test
    void decompressOnlyRestoresActiveBlocks() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first done")))
                .success());
        final var restored = compression.decompress("b1");
        assertTrue(restored.success(), restored.message());
        assertEquals(
                ContextCompression.Status.DECOMPRESSED,
                compression.blocks().get(0).status());
        assertEquals(5, compression.rewrite(messages).size(), "nothing is rewritten once decompressed");

        final var again = compression.decompress("@b1@");
        assertFalse(again.success());
        assertTrue(again.message().contains("cannot be decompressed"), again.message());

        final var unknown = compression.decompress("b99");
        assertFalse(unknown.success());
        assertTrue(unknown.message().contains("Unknown"), unknown.message());
    }

    @Test
    void absorbAllMarksEveryActiveBlockAbsorbed() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        compression.compress("first", List.of(new ContextCompression.Range("1", "2", "first done")));
        compression.compress("next", List.of(new ContextCompression.Range("3", "4", "second done")));
        compression.absorbAll();
        assertTrue(
                compression.blocks().stream().allMatch(block -> block.status() == ContextCompression.Status.ABSORBED));
        assertEquals(0, compression.savedTokens(messages));
    }

    @Test
    void protectionsAppendUserMessagesTagsAndToolOutputs() {
        final var compression = new ContextCompression(protectedConfig());
        final var messages = toolConversation();
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "first", List.of(new ContextCompression.Range("1", "3", "work done: fixed the rounding")));
        assertTrue(result.success(), result.message());
        final var summary = compression.blocks().get(0).summary();
        assertTrue(summary.contains("task one"), summary);
        assertTrue(summary.contains("[protected tool output: my_tool]"), summary);
        assertTrue(summary.contains("OK from my_tool"), summary);
    }

    @Test
    void turnProtectionRefusesToCompressActiveWork() {
        final var compression = new ContextCompression(protectedConfig()); // turnProtection=true, turns=1
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var oldTurn = compression.compress("old", List.of(new ContextCompression.Range("1", "2", "old turn")));
        assertTrue(oldTurn.success(), oldTurn.message());
        final var active = compression.compress("active", List.of(new ContextCompression.Range("3", "4", "no")));
        assertFalse(active.success());
        assertTrue(active.message().contains("protected from compression"), active.message());
    }

    @Test
    void disabledCompressionRefusesEverything() {
        final var disabled = new CompressConfiguration(
                false, "50000", "100000", 15, 5, true, false, false, List.of(), false, 4, true, true, 4);
        final var compression = new ContextCompression(disabled);
        assertFalse(compression.enabled());
        final var result = compression.compress("t", List.of(new ContextCompression.Range("1", "2", "s")));
        assertFalse(result.success());
        assertTrue(result.message().contains("disabled"), result.message());
    }

    @Test
    void disabledCompressionReturnsInputUnchanged() {
        final var disabled = new ContextCompression(new CompressConfiguration(
                false, "50000", "100000", 15, 5, true, false, false, List.of(), false, 4, true, true, 4));
        final var messages = simpleConversation();
        assertSame(messages, disabled.renderRefs(messages));
        assertSame(messages, disabled.rewrite(messages));
        assertEquals(0, disabled.savedTokens(messages));
    }
}
