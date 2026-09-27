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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationTranscriptTest {
    @Test
    void foldsTurnEventsIntoTranscript() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ThinkingDelta("let me", "s1"));
        t.onEvent(new AgentEvent.ToolStarted("read_file", "{\"path\":\"a.txt\"}", "s1"));
        t.onEvent(new AgentEvent.ToolStream("read_file", "body", "s1"));
        t.onEvent(new AgentEvent.ToolFinished("read_file", "body", true, "s1"));
        t.onEvent(new AgentEvent.TurnDone("answer", "s1"));

        final var md = t.transcript().toString();
        assertTrue(md.contains("**You**\n\nhi"));
        assertTrue(md.contains(ConversationTranscript.THOUGHT_BEGIN));
        assertTrue(md.contains("**Thinking**"));
        assertTrue(md.contains(ConversationTranscript.TOOL_BEGIN));
        assertTrue(md.contains("*streaming `read_file`: a.txt*"));
        assertTrue(md.contains("```text\nbody\n```"));
        assertTrue(md.contains("**Assistant**\n\nanswer"));
        assertFalse(t.busy());
        assertEquals("ready", t.status());
    }

    @Test
    void failedToolIsRecordedAsBlockquote() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1"));
        t.onEvent(new AgentEvent.ToolFinished("bash", "boom\nsecond", false, "s1"));

        final var md = t.transcript().toString();
        assertTrue(md.contains("> tool `bash` failed: boom"));
    }

    @Test
    void textResetFlushesThinkingInsteadOfDroppingIt() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("reasoning deeply", "s1"));
        t.onEvent(new AgentEvent.TextReset("s1"));
        assertEquals("", t.thinking().toString());
        assertTrue(t.transcript()
                .toString()
                .contains(ConversationTranscript.THOUGHT_BEGIN + "\n**Thinking**\n\nreasoning deeply\n"
                        + ConversationTranscript.THOUGHT_END));
    }

    @Test
    void thinkTagAtTurnDoneBecomesThoughtRegion() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnDone("<think>\nstep one\nstep two\n</think>\n\nThe answer", "s1"));
        final var transcript = t.transcript().toString();
        assertTrue(transcript.contains(ConversationTranscript.THOUGHT_BEGIN));
        assertTrue(transcript.contains("**Thinking**\n\nstep one\nstep two"));
        assertTrue(transcript.contains(ConversationTranscript.THOUGHT_END));
        // raw text keeps the newline that followed </think>
        assertTrue(transcript.contains("The answer"));
        assertTrue(
                ConversationTranscript.regions(transcript).stream().anyMatch(r -> r.thought()),
                "expected a thought region in: " + transcript);
    }

    @Test
    void thinkTagStreamsLiveOnThoughtPanel() {
        final var t = new ConversationTranscript();
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.TextDelta("<think>\nreasoning line\n</think>\n\ndrafting", "s1"));
        // live reasoning is classified into the thinking buffer, visible text into pending
        assertTrue(
                t.thinking().toString().contains("reasoning line"), t.thinking().toString());
        // the raw newlines after </think> stay in the visible stream
        assertEquals("\n\ndrafting", t.pending().toString());
    }

    @Test
    void fencedThinkTagUntouched() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnDone("```text\n<think>not a thought</think>\n```\n\nok", "s1"));
        final var transcript = t.transcript().toString();
        assertFalse(transcript.contains(ConversationTranscript.THOUGHT_BEGIN), transcript);
        assertTrue(transcript.contains("<think>not a thought</think>"));
    }

    @Test
    void unclosedThinkTailAndWhitespaceTagAreReasoning() {
        final var parts = new ThinkTagSplitter().split("<think >\npartial reasoning");
        assertEquals("", parts.visible());
        assertEquals("partial reasoning", parts.thinking().strip());
        final var plain = new ThinkTagSplitter().split("plain text");
        assertEquals("plain text", plain.visible());
        assertEquals("", plain.thinking());
    }

    @Test
    void firstAnswerDeltaFlushesThinkingAboveAnswer() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("reasoning deeply", "s1"));
        t.onEvent(new AgentEvent.TextDelta("answer part", "s1"));
        assertEquals("", t.thinking().toString());
        assertEquals("answer part", t.pending().toString());
        assertTrue(t.transcript()
                .toString()
                .contains(ConversationTranscript.THOUGHT_BEGIN + "\n**Thinking**\n\nreasoning deeply\n"
                        + ConversationTranscript.THOUGHT_END));
    }

    @Test
    void errorKeepsThinkingVisibleAboveTheErrorLine() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.ThinkingDelta("why it broke", "s1"));
        t.onEvent(new AgentEvent.Error("kaput", "s1"));
        assertEquals("", t.thinking().toString());
        assertFalse(t.busy());
        final var transcript = t.transcript().toString();
        assertTrue(transcript.contains("why it broke"));
        assertTrue(transcript.contains("> error: kaput"));
        assertTrue(
                transcript.indexOf("why it broke") < transcript.indexOf("> error: kaput"),
                "expected reasoning to stay above the error line: " + transcript);
    }

    @Test
    void runningToolBlockIsWrappedInToolMarkers() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"tail -f log\"}", "s1"));
        t.onEvent(new AgentEvent.ToolStream("bash", "streamed line", "s1"));

        final var md = t.markdown("|", "welcome");
        assertTrue(md.contains(ConversationTranscript.TOOL_BEGIN));
        assertTrue(md.contains(ConversationTranscript.TOOL_END));
        assertTrue(md.contains("`| running tail -f log`"));
        assertTrue(md.contains("```text\nstreamed line\n```"));

        final var regions = ConversationTranscript.regions(md);
        assertTrue(regions.stream().anyMatch(ConversationTranscript.Region::tool));
    }

    @Test
    void turnMetricsKeepDurationAndResetClearsIt() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500, "s1"));
        assertEquals(3, t.turnLlmCalls());
        assertEquals(1500, t.turnDurationMs());
        t.onEvent(new AgentEvent.ThinkingDelta("thought", "s1"));
        assertEquals("thought", t.thinkingText());
        assertEquals(7, t.thinkingLength());

        t.reset();
        assertEquals(0, t.turnDurationMs());
        assertEquals("", t.thinkingText());
    }

    @Test
    void statusNoticeAndUsageAreTracked() {
        final var t = new ConversationTranscript();
        t.onEvent(AgentEvent.Notice.warn("careful", "s1"));
        assertEquals("careful", t.status());
        assertTrue(t.transcript().toString().contains("> ⚠ warning: careful"));

        t.onEvent(new AgentEvent.Status("working", "s1"));
        assertEquals("working", t.status());

        t.onEvent(new AgentEvent.UsageUpdated(null, 42, 12.4, "s1"));
        assertEquals(42, t.estimatedContext());
        assertEquals(12, t.contextPercent());
    }

    @Test
    void replayAppendsPersistedHistory() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.system("ignored"),
                Message.user("question"),
                Message.assistant("reply", null),
                Message.tool("call-1", "tool output\nmore")));

        final var md = t.transcript().toString();
        assertFalse(md.contains("ignored"));
        assertTrue(md.contains("---\n\n**You**\n\nquestion"));
        assertTrue(md.contains("---\n\n**Assistant**\n\nreply"));
        assertTrue(md.contains("*tool -> tool output*"));
    }

    @Test
    void replayResolvesToolNameFromAssistantCall() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.assistant(
                        "reading", List.of(new ToolCall("call-1", "function", new FunctionCall("grep", "{}"), null))),
                Message.tool("call-1", "tool output\nmore")));

        final var md = t.transcript().toString();
        assertTrue(md.contains("*tool `grep` -> tool output*"));
        assertFalse(md.contains("null"));
    }

    @Test
    void replayUsesStoredToolNameOnToolMessage() {
        final var t = new ConversationTranscript();
        t.replay(List.of(
                Message.assistant(
                        "reading", List.of(new ToolCall("call-1", "function", new FunctionCall("grep", "{}"), null))),
                Message.toolResult("call-1", "tool output\nmore", "grep")));

        final var md = t.transcript().toString();
        assertTrue(md.contains("*tool `grep` -> tool output*"));
        assertFalse(md.contains("null"));
    }

    @Test
    void regionsSplitThoughtAndToolBlocks() {
        final var md = "before"
                + ConversationTranscript.THOUGHT_BEGIN
                + "thinking"
                + ConversationTranscript.THOUGHT_END
                + "middle"
                + ConversationTranscript.TOOL_BEGIN
                + "tool"
                + ConversationTranscript.TOOL_END
                + "after";

        final var regions = ConversationTranscript.regions(md);
        assertEquals(5, regions.size());
        assertEquals(new ConversationTranscript.Region("before", false, false), regions.get(0));
        assertTrue(regions.get(1).thought());
        assertTrue(regions.get(3).tool());
        assertEquals("after", regions.get(4).text());
    }

    @Test
    void trimKeepsTailAndDropsDanglingRegions() {
        final var t = new ConversationTranscript();
        t.transcript().append("x\n".repeat(100));
        t.flushThinking();
        t.maxHistoryCharacters(50);
        t.trim();

        final var md = t.transcript().toString();
        assertTrue(md.length() < 100);
        assertFalse(md.contains(ConversationTranscript.THOUGHT_BEGIN));
        assertFalse(md.contains(ConversationTranscript.THOUGHT_END));
    }

    @Test
    void resetClearsEverything() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.TextDelta("streaming", "s1"));
        t.onEvent(new AgentEvent.UsageUpdated(null, 10, 5.0, "s1"));
        t.reset();

        assertEquals("", t.transcript().toString());
        assertEquals("", t.pending().toString());
        assertFalse(t.busy());
        assertEquals("", t.status());
        assertEquals(0, t.estimatedContext());
        assertEquals(0, t.turnLlmCalls());
    }

    @Test
    void queuedPromptIntegratesIntoRunningTurn() {
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ThinkingDelta("thought", "s1"));
        t.onEvent(new AgentEvent.PromptIntegrated("follow up", "s1"));

        assertTrue(t.busy(), "the turn must stay busy while a queued prompt runs");
        assertEquals("queued prompt integrated", t.status());
        assertEquals("", t.thinking().toString(), "pending thinking must be flushed into the transcript");
        final var md = t.transcript().toString();
        assertTrue(md.contains("thought"), "flushed thinking must be visible above the prompt: " + md);
        assertTrue(md.contains("**You**\n\nfollow up"), "queued prompt must be folded under a You block: " + md);
        assertTrue(md.indexOf("thought") < md.indexOf("**You**\n\nfollow up"));

        t.onEvent(new AgentEvent.TurnDone("answer", "s1"));
        assertFalse(t.busy());
    }

    @Test
    void parallelToolsEachKeepTheirOwnStreamAndCommand() {
        final var t = new ConversationTranscript();
        // two parallel read-only tools interleave their starts and streams
        t.onEvent(new AgentEvent.ToolStarted("read_file", "{\"path\":\"a.txt\"}", "s1"));
        t.onEvent(new AgentEvent.ToolStream("read_file", "content-of-a", "s1"));
        t.onEvent(new AgentEvent.ToolStarted("grep", "{\"command\":\"grep -r x\"}", "s1"));
        t.onEvent(new AgentEvent.ToolStream("grep", "match-line", "s1"));
        // A finishes first: its card must carry A's command and A's stream only
        t.onEvent(new AgentEvent.ToolFinished("read_file", "content-of-a", true, "s1"));
        final var mdAfterA = t.transcript().toString();
        assertTrue(mdAfterA.contains("*streaming `read_file`: a.txt*"), mdAfterA);
        assertTrue(mdAfterA.contains("content-of-a"), mdAfterA);
        assertFalse(mdAfterA.contains("match-line"), "B's stream must not leak into A's card: " + mdAfterA);
        // B finishes: its own card with its own content, ordinal strictly after A's
        t.onEvent(new AgentEvent.ToolFinished("grep", "match-line", true, "s1"));
        final var md = t.transcript().toString();
        assertTrue(md.contains("*streaming `grep`: grep -r x*"), md);
        assertTrue(md.contains("match-line"), md);
        assertEquals(2, t.toolCallCount(), "one ordinal per finished call, in finish order");
        final var iA = md.indexOf("read_file");
        final var iB = md.indexOf("grep");
        assertTrue(iA < iB, "cards must follow finish order: " + md);
    }

    @Test
    void subagentStreamsNeverPolluteTheParentTranscript() {
        final var t = new ConversationTranscript();
        // subagent-tagged events are dropped from a parent transcript...
        t.onEvent(new AgentEvent.ThinkingDelta("child reasoning", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.TextDelta("child answer", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.ToolFinished("bash", "out", true, "s1", "sub-1", "child"));
        assertEquals("", t.transcript().toString());
        assertEquals("", t.pending().toString());
        assertEquals("", t.thinking().toString());
        // ...while the lifecycle markers are still folded in
        t.onEvent(new AgentEvent.SubagentStarted("do the thing", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.SubagentCompleted("done", true, "s1", "sub-1", "child"));
        final var md = t.transcript().toString();
        assertTrue(md.contains("> subagent `child` started: do the thing"), md);
        assertTrue(md.contains("> subagent `child` completed: done"), md);
        assertFalse(md.contains("child reasoning"), md);
        assertFalse(md.contains("child answer"), md);
        assertFalse(md.contains("bash"), md);
    }

    @Test
    void captureTranscriptFoldsSubagentStreams() {
        final var t = new ConversationTranscript(true);
        t.startUserTurn("go");
        t.onEvent(new AgentEvent.ThinkingDelta("child reasoning", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.TextDelta("child answer", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", "sub-1", "child"));
        t.onEvent(new AgentEvent.ToolFinished("bash", "out", true, "s1", "sub-1", "child"));
        final var md = t.markdown("|", "");
        assertTrue(md.contains("child reasoning"), md);
        assertTrue(md.contains("child answer"), md);
        assertTrue(md.contains("out"), md);
    }

    @Test
    void retryableErrorClearsFromStatusOnNextProgress() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.Notice(AgentEvent.Notice.Level.ERROR, "LLM request failed (429) retry-after=1", "s1"));
        assertTrue(t.status().startsWith("error:"), t.status());
        t.onEvent(new AgentEvent.TextDelta("recovered", "s1"));
        assertFalse(t.status().startsWith("error:"), "a successful retry must clear the stale error: " + t.status());
    }

    @Test
    void fatalErrorSurvivesTurnProgress() {
        final var t = new ConversationTranscript();
        t.onEvent(new AgentEvent.Error("kaput", "s1"));
        t.onEvent(new AgentEvent.TextDelta("still streaming", "s1"));
        assertEquals("error: kaput", t.status(), "non-retryable errors stay until acknowledged");
    }
}
