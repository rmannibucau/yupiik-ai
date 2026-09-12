package io.yupiik.ia.agent.tui;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiTranscriptModelTest {
    private final TuiTranscriptModel model = new TuiTranscriptModel();

    @Test
    void freshModelFallsBackToWelcomeMarkdown() {
        assertFalse(model.busy());
        assertEquals("", model.status());
        assertEquals("welcome", model.markdown("|", "welcome"));
    }

    @Test
    void startUserTurnAppendsUserBlockAndSetsBusy() {
        model.startUserTurn("do it");
        assertTrue(model.transcript().toString().contains("**You**\n\ndo it"));
        assertTrue(model.busy());
        assertEquals(0, model.scrollBack());
        assertTrue(model.markdown("|", "welcome").contains("do it"));
    }

    @Test
    void textDeltaAccumulatesPendingAndShowsItWhileBusy() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("hel"));
        model.onEvent(new AgentEvent.TextDelta("lo"));
        assertEquals("hello", model.pending().toString());
        assertTrue(model.markdown("|", "welcome").contains("**Assistant**\n\nhello"));
    }

    @Test
    void turnDoneFlushesPendingAndClosesTurn() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("partial"));
        model.onEvent(new AgentEvent.TurnDone("final answer"));
        assertTrue(model.transcript().toString().contains("**Assistant**\n\nfinal answer"));
        assertEquals("", model.pending().toString());
        assertFalse(model.busy());
        assertEquals("ready", model.status());
    }

    @Test
    void flushThinkingWrapsThoughtBetweenMarkersOnce() {
        model.onEvent(new AgentEvent.ThinkingDelta("let me "));
        model.onEvent(new AgentEvent.ThinkingDelta("think"));
        model.flushThinking();
        final var transcript = model.transcript().toString();
        assertTrue(transcript.contains(TuiTranscriptModel.THOUGHT_BEGIN + "\n**Thinking**\n\nlet me think\n" + TuiTranscriptModel.THOUGHT_END));
        model.flushThinking();
        assertEquals(1, count(transcript, TuiTranscriptModel.THOUGHT_BEGIN));
    }

    @Test
    void flushThinkingWithBlankContentWritesNothing() {
        model.onEvent(new AgentEvent.ThinkingDelta("   "));
        model.flushThinking();
        assertEquals("", model.transcript().toString());
    }

    @Test
    void toolStartedExtractsCommandAndResetsStream() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}"));
        assertEquals("bash", model.runningTool());
        assertEquals("ls -la", model.runningToolCommand());
        model.onEvent(new AgentEvent.ToolStream("bash", "file.txt"));
        model.onEvent(new AgentEvent.ToolStarted("read", "{}"));
        assertEquals("", model.runningToolCommand());
        final var md = model.markdown("|", "welcome");
        assertTrue(md.contains("running read`"), "expected running line in: " + md);
        assertFalse(md.contains("file.txt"), "stream must have been reset: " + md);
    }

    @Test
    void markdownShowsRunningToolAndStreamedOutput() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}"));
        model.onEvent(new AgentEvent.ToolStream("bash", "out.txt"));
        final var md = model.markdown("*", "welcome");
        assertTrue(md.contains("`* running ls`"));
        assertTrue(md.contains("```text\nout.txt```"));
    }

    @Test
    void toolStreamIgnoresOtherToolsAndCapsSize() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{}"));
        model.onEvent(new AgentEvent.ToolStream("other", "nope"));
        assertEquals("", model.transcript().toString());
        model.onEvent(new AgentEvent.ToolStream("bash", "x".repeat(2000)));
        assertTrue(model.markdown("|", "welcome").length() < 2200, "stream must stay capped");
    }

    @Test
    void toolFinishedFlushesStreamAsToolRegion() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls\"}"));
        model.onEvent(new AgentEvent.ToolStream("bash", "a.txt"));
        model.onEvent(new AgentEvent.ToolFinished("bash", "", true));
        final var transcript = model.transcript().toString();
        assertTrue(transcript.contains(TuiTranscriptModel.TOOL_BEGIN));
        assertTrue(transcript.contains("*streaming `bash`: ls*"));
        assertTrue(transcript.contains("```text\na.txt\n```"));
        assertTrue(transcript.contains(TuiTranscriptModel.TOOL_END));
        assertNull(model.runningTool());
    }

    @Test
    void failedToolIsReported() {
        model.onEvent(new AgentEvent.ToolStarted("bash", "{}"));
        model.onEvent(new AgentEvent.ToolFinished("bash", "boom\n details", false));
        assertTrue(model.transcript().toString().contains("> tool `bash` failed: boom"));
    }

    @Test
    void usageUpdatedTracksTokens() {
        model.onEvent(new AgentEvent.UsageUpdated(null, 42000, 55.44));
        assertEquals(0, model.promptTokens());
        assertEquals(0, model.completionTokens());
        assertEquals(42000, model.estimatedContext());
        assertEquals(55, model.contextPercent());
    }

    @Test
    void compactedUpdatesStatusAndTranscript() {
        model.onEvent(new AgentEvent.Compacted("auto"));
        assertEquals("context auto done (summary)", model.status());
        assertTrue(model.transcript().toString().contains("> context auto applied (summary)"));
    }

    @Test
    void noticeRendersDistinctTranscriptAndStatus() {
        model.onEvent(AgentEvent.Notice.error("boom"));
        assertTrue(model.transcript().toString().contains("> ⚠ error: boom"));
        assertEquals("error: boom", model.status());
        model.onEvent(AgentEvent.Notice.info("all good"));
        assertTrue(model.transcript().toString().contains("> ⓘ: all good"));
    }

    @Test
    void statusAndErrorEventsMapDirectly() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("draft"));
        model.onEvent(new AgentEvent.Status("working"));
        assertEquals("working", model.status());
        model.onEvent(new AgentEvent.Error("kaput"));
        assertTrue(model.transcript().toString().contains("> error: kaput"));
        assertFalse(model.busy());
        assertEquals("error: kaput", model.status());
        assertEquals("", model.pending().toString());
    }

    @Test
    void regionsParsesPlainThoughtAndToolBlocks() {
        final var md = "intro\n\n"
                + TuiTranscriptModel.THOUGHT_BEGIN + "\nthink\n" + TuiTranscriptModel.THOUGHT_END + "\n"
                + "middle\n\n"
                + TuiTranscriptModel.TOOL_BEGIN + "\ntool body\n" + TuiTranscriptModel.TOOL_END + "\n"
                + "outro";
        final var regions = TuiTranscriptModel.regions(md);
        assertEquals(5, regions.size());
        assertEquals("intro", regions.get(0).text().strip());
        assertFalse(regions.get(0).thought());
        assertFalse(regions.get(0).tool());
        assertTrue(regions.get(1).thought());
        assertFalse(regions.get(1).tool());
        assertEquals("think", regions.get(1).text().strip());
        assertEquals("middle", regions.get(2).text().strip());
        assertTrue(regions.get(3).tool());
        assertFalse(regions.get(3).thought());
        assertEquals("tool body", regions.get(3).text().strip());
        assertEquals("outro", regions.get(4).text().strip());
        assertFalse(regions.get(4).thought());
        assertFalse(regions.get(4).tool());
    }

    @Test
    void regionsToleratesDanglingMarkers() {
        assertTrue(TuiTranscriptModel.regions(TuiTranscriptModel.THOUGHT_END + "\nstray end").size() >= 1);
        assertTrue(TuiTranscriptModel.regions("start " + TuiTranscriptModel.TOOL_BEGIN + "\nnever closed").size() >= 1);
    }

    @Test
    void trimKeepsTranscriptUnderLimitOnLineBoundaries() {
        model.maxHistoryCharacters(40);
        model.transcript().setLength(0);
        model.transcript().append("aaaaaaaaaa\nbbbbbbbbbb\ncccccccccc\ndddddddddd\neeeeeeeeee\nffffffffff");
        model.trim();
        final var trimmed = model.transcript().toString();
        assertTrue(trimmed.length() <= 40, "expected trimmed transcript: " + trimmed);
        assertTrue(trimmed.startsWith("cccccccccc") || trimmed.startsWith("dddddddddd") || trimmed.startsWith("eeeeeeeeee") || trimmed.startsWith("ffffffffff"),
                "expected trim to align on a line boundary: " + trimmed);
    }

    @Test
    void trimDropsDanglingRegions() {
        model.maxHistoryCharacters(30);
        model.transcript().setLength(0);
        model.transcript().append(TuiTranscriptModel.TOOL_BEGIN + "\nlong tool output spanning lines\n" + TuiTranscriptModel.TOOL_END + "\ntail");
        model.trim();
        final var trimmed = model.transcript().toString();
        assertEquals(count(trimmed, TuiTranscriptModel.TOOL_BEGIN), count(trimmed, TuiTranscriptModel.TOOL_END),
                "trimmed transcript must not carry a dangling tool region: " + trimmed);
    }

    @Test
    void trimIgnoresSmallTranscripts() {
        model.maxHistoryCharacters(10_000);
        model.transcript().setLength(0);
        model.transcript().append("small");
        model.trim();
        assertEquals("small", model.transcript().toString());
    }

    @Test
    void clearConversationResetsConversationButKeepsInstance() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.Status("busy"));
        model.clearConversation();
        assertEquals("", model.transcript().toString());
        assertEquals("", model.thinking().toString());
        assertEquals(0, model.scrollBack());
        assertEquals("busy", model.status());
    }

    @Test
    void resetClearsEverything() {
        model.startUserTurn("hi");
        model.onEvent(new AgentEvent.TextDelta("x"));
        model.onEvent(new AgentEvent.ToolStarted("bash", "{}"));
        model.onEvent(new AgentEvent.Status("busy"));
        model.reset();
        assertFalse(model.busy());
        assertEquals("", model.status());
        assertEquals("", model.transcript().toString());
        assertEquals("", model.pending().toString());
        assertEquals("", model.thinking().toString());
        assertNull(model.runningTool());
        assertEquals(0, model.scrollBack());
        assertEquals(0, model.promptTokens());
        assertEquals(0, model.completionTokens());
        assertEquals(0, model.estimatedContext());
        assertEquals(0, model.contextPercent());
    }

    private static int count(final String value, final String needle) {
        var count = 0;
        for (int i = value.indexOf(needle); i >= 0; i = value.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
