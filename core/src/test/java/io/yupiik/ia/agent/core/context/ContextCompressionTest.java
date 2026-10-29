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
import io.yupiik.ia.agent.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
        final var compression = new ContextCompression(null);
        assertEquals("done\ncontinue", compression.stripEchoedRefs("done\n@90@\ncontinue"));
        assertEquals("just a ref", compression.stripEchoedRefs("just a ref @7@"));
        assertEquals("nested sum", compression.stripEchoedRefs("nested @b2@ sum"));
        assertEquals("blocked turn", compression.stripEchoedRefs("blocked @blocked@ turn"));
        assertEquals("prioritized", compression.stripEchoedRefs("prioritized @3@ [high]"));
        assertEquals("no refs here", compression.stripEchoedRefs("no refs here"));
        assertEquals("keeps at-word mention @user", compression.stripEchoedRefs("keeps at-word mention @user"));
        assertNull(compression.stripEchoedRefs(null));
    }

    @Test
    void withholdDanglingPrefixHidesOnlyPartialPrefixesOfLiveRefs() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        // refs are assigned by the wire builder, not scraped from message content: the user is @1@
        compression.renderRefs(messages);
        // a trailing '@24' (not a live ref) must be kept: it is plain content
        assertEquals("done\n@24", compression.withholdDanglingPrefix("done\n@24"));
        // '@1' is a strict prefix of the live '@1@', so the half-arrived fragment is withheld
        assertEquals("reasoning about step ", compression.withholdDanglingPrefix("reasoning about step @1"));
        // a complete live ref is removed entirely
        assertEquals("answer used it", compression.withholdDanglingPrefix("answer @1@ used it"));
        assertNull(compression.withholdDanglingPrefix(null));
    }

    @Test
    void withholdDanglingPrefixIgnoresGluedProseBeforeTheFragment() {
        // the trailing-run scan must not walk back over the prose glued to the
        // fragment ("answer@1"): only the token part anchored on the last '@' is a
        // candidate, the prose before it stays visible
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        compression.renderRefs(messages);
        assertEquals("answer", compression.withholdDanglingPrefix("answer@1"));
        // without glued prose the same fragment is still withheld
        assertEquals("answer ", compression.withholdDanglingPrefix("answer @1"));
    }

    @Test
    void withholdDanglingPrefixHidesStandaloneMultilineEchoWithLiveRefEvidence() {
        // a literal newline can split the echoed seam across visual lines
        // ("@3149\n@"): when the reconstructed token matches a live ref, both the
        // fragment and its trailing closing delimiter line are display-only noise
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        compression.renderRefs(messages);
        assertEquals("done\n", compression.withholdDanglingPrefix("done\n@1\n@"));
        assertEquals("done\n", compression.withholdDanglingPrefix("done\n@1\n\n@"));
        assertEquals("done\n", compression.withholdDanglingPrefix("done\n@1\r\n@"));
        // no live ref matches -> real content, untouched (e.g. a fenced '@9\n@' snippet);
        // only the lone closing '@' is withheld (partial prefix of every ref)
        assertEquals("done\n@9\n", compression.withholdDanglingPrefix("done\n@9\n@"));
    }

    @Test
    void withholdDanglingPrefixNeverMatchesAcrossProse() {
        // the multiline reconstruction is only for a fragment sitting alone on its
        // own trailing lines: prose between the fragment and the closing '@' blocks
        // it, so the prose and the fragment line stay visible; the lone trailing '@' is
        // withheld by the existing partial-prefix rule (strict prefix of every ref)
        final var compression = new ContextCompression(config());
        final var messages = List.of(Message.system("system"), Message.user("instruction"));
        compression.publish(messages);
        compression.renderRefs(messages);
        assertEquals("done\n@1\nreal prose\n", compression.withholdDanglingPrefix("done\n@1\nreal prose\n@"));
    }

    @Test
    void compactedMessageRefLeavesTheWithholdWhitelist() {
        final var compression = new ContextCompression(config());
        final var system = Message.system("system");
        final var old = Message.user("old turn");
        compression.publish(List.of(system, old));
        compression.renderRefs(List.of(system, old));
        // @1@ is live now -> its strict prefix '@1' is withheld
        assertEquals("tail ", compression.withholdDanglingPrefix("tail @1"));
        // after compaction the message leaves the wire -> @1@ no longer live -> '@1' stays as content
        final var fresh = Message.assistant("new answer", List.of());
        compression.publish(List.of(system, fresh));
        compression.renderRefs(List.of(system, fresh));
        assertEquals("tail @1", compression.withholdDanglingPrefix("tail @1"));
        // the newly rendered message gets its own live seam
        assertEquals("tail ", compression.withholdDanglingPrefix("tail @2"));
    }

    @Test
    void seamWhitelistDoesNotFollowTheLiveWorkingList() {
        final var compression = new ContextCompression(config());
        final var live = new ArrayList<>(List.of(Message.system("system"), Message.user("instruction")));
        compression.publish(live);
        compression.renderRefs(List.copyOf(live));
        assertEquals("tail ", compression.withholdDanglingPrefix("tail @1"));
        // the agent keeps mutating the published list in place between rounds; the render whitelist
        // must stay on the wire snapshot instead of walking that list
        live.add(Message.assistant("late answer", List.of()));
        live.clear();
        live.add(Message.system("system"));
        assertEquals("tail ", compression.withholdDanglingPrefix("tail @1"));
    }

    @Test
    void withholdDanglingPrefixIsSafeWhileTheLiveListIsMutated() throws InterruptedException {
        final var compression = new ContextCompression(config());
        final var live = new ArrayList<Message>();
        live.add(Message.system("system"));
        for (var i = 0; i < 64; i++) {
            live.add(Message.user("turn " + i));
        }
        compression.publish(live);
        compression.renderRefs(List.copyOf(live));
        final var stop = new AtomicBoolean();
        final var writer = new Thread(() -> {
            while (!stop.get()) {
                live.add(Message.assistant("streamed", List.of()));
                live.remove(live.size() - 1);
            }
        });
        writer.start();
        try {
            for (var i = 0; i < 10_000; i++) {
                compression.withholdDanglingPrefix("tail @1");
            }
        } finally {
            stop.set(true);
            writer.join(1_000);
        }
    }

    @Test
    void publishPrunesMessagesThatLeftTheWire() {
        final var compression = new ContextCompression(null);
        final var first = new Message("user", "one", null, null, null, Map.of());
        final var second = new Message("user", "two", null, null, null, Map.of());
        compression.publish(List.of(first, second));
        final var ref1 = compression.refOf(first);
        final var ref2 = compression.refOf(second);
        // second stays on the wire, first is pruned
        compression.publish(List.of(second));
        assertSame(second, compression.messageOf(ref2));
        assertEquals(null, compression.messageOf(ref1));
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
    void targetsExtendsTheHardLimitBySummaryTokensWhenBuffered() {
        final var compression = new ContextCompression(config()); // summaryBuffer=true
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result =
                compression.compress("first task", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertTrue(result.success(), result.message());
        assertTrue(compression.summaryTokens() > 0, "a summary must carry tokens");
        final var targets = compression.targets(200_000);
        assertEquals(50_000, targets.baseMin());
        assertEquals(100_000, targets.baseMax());
        assertEquals(100_000 + compression.summaryTokens(), targets.effectiveMax());
        assertTrue(targets.summaryBuffer());
    }

    @Test
    void targetsKeepsHardLimitWhenSummaryBufferIsOff() {
        final var noBuffer = new CompressConfiguration(
                true, "50000", "100000", 15, 5, false, false, false, List.of("my_tool"), false, 4, true, true, 4);
        final var compression = new ContextCompression(noBuffer);
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var result =
                compression.compress("first task", List.of(new ContextCompression.Range("1", "2", "first task done")));
        assertTrue(result.success(), result.message());
        final var targets = compression.targets(200_000);
        assertEquals(100_000, targets.effectiveMax());
        assertFalse(targets.summaryBuffer());
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
        assertTrue(result.message().contains("overlap"), result.message());
        assertTrue(result.message().contains("Send disjoint small ranges"), result.message());
        assertTrue(compression.blocks().isEmpty(), "nothing is compressed when ranges overlap");
    }

    @Test
    void overlappingActiveBlockIsSkippedWithNudge() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        assertTrue(compression
                .compress("first", List.of(new ContextCompression.Range("1", "2", "first done")))
                .success());
        final var withoutRef =
                compression.compress("greedy", List.of(new ContextCompression.Range("1", "4", "no placeholder")));
        assertTrue(withoutRef.success(), withoutRef.message());
        assertTrue(withoutRef.message().contains("overlaps active block"), withoutRef.message());
        assertEquals(1, compression.blocks().size(), "the skipped overlapped range must not create a block");
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
    void protectsRuntimeToolResultWithNameResolvedFromAssistantCall() {
        final var compression = new ContextCompression(config());
        final var messages = List.of(
                Message.system("system"),
                Message.user("task one"),
                Message.assistant(
                        "calling",
                        List.of(new ToolCall("call-1", "function", new ToolCall.FunctionCall("my_tool", "{}"), 0))),
                Message.tool("call-1", "OK from my_tool"),
                Message.user("task two"),
                Message.assistant("done", List.of()));
        assignedRefs(compression, messages);

        final var result = compression.compress(
                "first", List.of(new ContextCompression.Range("1", "3", "work done: fixed the rounding")));

        assertTrue(result.success(), result.message());
        final var summary = compression.blocks().getFirst().summary();
        assertTrue(summary.contains("[protected tool output: my_tool]"), summary);
        assertTrue(summary.contains("OK from my_tool"), summary);
    }

    @Test
    void turnProtectionSkipsTouchingRangeButAppliesOthers() {
        final var compression = new ContextCompression(protectedConfig()); // turnProtection=true, turns=1
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        final var oldTurn = compression.compress("old", List.of(new ContextCompression.Range("1", "2", "old turn")));
        assertTrue(oldTurn.success(), oldTurn.message());
        // range 3..4 touches the last protected user turn -> per-item skip, not a batch hard-fail
        final var active = compression.compress("active", List.of(new ContextCompression.Range("3", "4", "no")));
        assertTrue(active.success(), active.message());
        assertTrue(active.message().contains("protected"), active.message());
        assertTrue(active.message().contains("skipped"), active.message());
        assertEquals(1, compression.blocks().size(), "the protected range must not create a block");
    }

    @Test
    void mixedBatchAppliesGoodRangesAndSkipsBadOnes() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation(); // refs: 1 user, 2 assistant, 3 user, 4 assistant
        assignedRefs(compression, messages);
        // range 1..2 is valid (assistant ref 2 ends at a turn boundary before user ref 3); the second
        // range 2..2 (assistant, no later user before its end) cannot snap -> skipped, and the good
        // range must still be applied so a single bad range never rejects the batch
        final var result = compression.compress(
                "t",
                List.of(
                        new ContextCompression.Range("1", "2", "t1 done"),
                        new ContextCompression.Range("2", "2", "bad")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertEquals("b1", result.blockId());
        assertEquals(1, compression.blocks().size(), "only the good range is compressed");
    }

    @Test
    void volatileTailRangeIsSkippedNotRejectingBatch() {
        final var compression = new ContextCompression(config());
        // the live session-state tail is a user message flagged volatileTail and is never ref'd by
        // renderRefs, so a range citing it resolves to "not in the live conversation" and must be
        // skipped without rejecting the rest of the batch
        final var messages = List.of(
                Message.system("system"),
                Message.user("task one"),
                Message.assistant("done", List.of()),
                new Message("user", "live session-state tail", null, null, null, Map.of("volatileTail", true)));
        assignedRefs(compression, messages);
        final var result = compression.compress(
                "t",
                List.of(
                        new ContextCompression.Range("1", "2", "task one done"),
                        new ContextCompression.Range("3", "3", "tail")));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("skipped"), result.message());
        assertEquals(1, compression.blocks().size(), "only the non-volatile range is compressed");
    }

    @Test
    void malformedContentStillHardFails() {
        final var compression = new ContextCompression(config());
        final var messages = simpleConversation();
        assignedRefs(compression, messages);
        // top-level absent/empty range list stays a hard failure (nothing to take)
        assertFalse(compression.compress("t", List.of()).success());
        assertFalse(compression.compress("t", null).success());
        // a null element is a per-item skip (success) matching the non-null-skip behavior
        assertTrue(compression
                .compress("t", Arrays.asList((ContextCompression.Range) null))
                .success());
        assertTrue(compression.blocks().isEmpty());
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
