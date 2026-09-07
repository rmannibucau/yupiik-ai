package io.yupiik.ia.agent.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextWindowExceededExceptionTest {

    @Test
    void detectsContextLengthBodies() {
        assertTrue(ContextWindowExceededException.isCause(400,
                "This model's maximum context length is 128000 tokens..."));
        assertTrue(ContextWindowExceededException.isCause(413,
                "{\"error\":{\"message\":\"Please reduce the length of the messages\"}}"));
        assertTrue(ContextWindowExceededException.isCause(400,
                "{\"error\":{\"message\":\"Input is too long: context_length 999 > 800\"}}"));
    }

    @Test
    void ignoresNonContextErrors() {
        assertFalse(ContextWindowExceededException.isCause(400, "Authentication required"));
        assertFalse(ContextWindowExceededException.isCause(500, "internal server error"));
        assertFalse(ContextWindowExceededException.isCause(200, "maximum context length is fine here"));
        assertFalse(ContextWindowExceededException.isCause(400, null));
        assertFalse(ContextWindowExceededException.isCause(400, ""));
    }
}