package io.yupiik.ia.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPromptResolverTest {

    private final TodoPromptResolver resolver = new TodoPromptResolver();

    @Test
    void autoDefaultsToOpenAiBlockWhenFamilyUnknown() {
        final var resolved = resolver.resolve("auto", "https://mystery.example/v1");
        assertTrue(resolved.contains("Do not batch up several todos"));
        assertFalse(resolved.contains("VERY frequently"));
    }

    @Test
    void autoThatIsAnthropicFamilyPicksAnthropicBlock() {
        final var resolved = resolver.resolve("auto", "https://api.anthropic.com/v1/messages");
        assertTrue(resolved.contains("VERY frequently"));
    }

    @Test
    void autoThatIsGeminiFamilyPicksGeminiBlock() {
        final var resolved = resolver.resolve("auto", "https://generativelanguage.googleapis.com/v1");
        assertTrue(resolved.contains("structured task list"));
    }

    @Test
    void nullModeDefaultsToAuto() {
        final var resolved = resolver.resolve(null, "https://mystery.example/v1");
        assertTrue(resolved.contains("Do not batch up several todos"));
    }

    @Test
    void autoWithNullBaseUrlFallsBackToOpenAiBlock() {
        final var resolved = resolver.resolve("auto", null);
        assertTrue(resolved.contains("Do not batch up several todos"));
    }

    @Test
    void verbatimValueIsReturnedAsIs() {
        assertEquals("custom guidance", resolver.resolve("custom guidance", "https://api.openai.com/v1"));
    }

    @Test
    void literalFalseDisablesGuidance() {
        assertEquals("", resolver.resolve("false", "https://api.anthropic.com"));
    }

    @Test
    void blankValueDisablesGuidance() {
        assertEquals("", resolver.resolve("", "https://api.anthropic.com"));
        assertEquals("", resolver.resolve("   ", "https://api.anthropic.com"));
    }
}