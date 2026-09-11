package io.yupiik.ia.agent.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionEngineTest {
    private static CompletionEngine engine() {
        return new CompletionEngine(
                () -> List.of("gpt-5-mini", "gpt-5", "qwen3-coder"),
                () -> List.of("openai", "ollama"),
                () -> List.of("reviewer", "tester"),
                () -> List.of("11111111-aaaa (2026-09-01 10:00)", "22222222-bbbb (2026-09-02 11:00)"),
                () -> List.of("read_file", "write_file", "bash"),
                () -> List.of("coder", "writer"));
    }

    @Test
    void emptyTextHasNoSuggestion() {
        assertTrue(engine().suggest("", 0).empty());
    }

    @Test
    void completesSlashCommandPrefix() {
        final var s = engine().suggest("/per", 4);
        assertTrue(s.items().contains("/permissions"));
        assertEquals(0, s.replaceStart());
        assertEquals(4, s.replaceEnd());
    }

    @Test
    void completesModelParam() {
        final var s = engine().suggest("/model gpt", 10);
        assertTrue(s.items().contains("gpt-5-mini"));
        assertTrue(s.items().contains("gpt-5"));
        assertEquals(7, s.replaceStart());
        assertEquals(10, s.replaceEnd());
        assertEquals("model", s.label());
    }

    @Test
    void completesModeParam() {
        final var s = engine().suggest("/mode pl", 8);
        assertTrue(s.items().contains("plan"));
    }

    @Test
    void completesPermissionsParam() {
        final var s = engine().suggest("/permissions a", 14);
        assertTrue(s.items().contains("all"));
    }

    @Test
    void completesThinkingParam() {
        final var s = engine().suggest("/thinking h", 11);
        assertTrue(s.items().contains("hide"));
    }

    @Test
    void completesToolOutputParam() {
        final var s = engine().suggest("/tool-output ", 13);
        assertTrue(s.items().contains("show"));
        assertTrue(s.items().contains("hide"));
    }

    @Test
    void completesAgentNameOverPrefix() {
        final var s = engine().suggest("@cod", 4);
        assertTrue(s.items().contains("coder"));
    }

    @Test
    void fuzzyContainsMatching() {
        final var s = engine().suggest("/model mini", 11);
        assertTrue(s.items().contains("gpt-5-mini"));
    }

    @Test
    void noMatchIsEmpty() {
        assertTrue(engine().suggest("/mode zzz", 9).empty());
    }

    @Test
    void pathCompletionLooksUpFilesystem() {
        final var s = CompletionEngine.pathCandidates("src/m");
        assertEquals(List.of("src/main/"), s);
    }

    @Test
    void pathCompletionWithoutTokenListsDirectory() {
        assertTrue(!CompletionEngine.pathCandidates("").isEmpty());
    }
}