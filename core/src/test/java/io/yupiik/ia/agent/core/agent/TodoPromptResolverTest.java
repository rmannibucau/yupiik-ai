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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
        assertEquals(
                "custom guidance", resolver.resolve("custom guidance", "https://api.openai.com/v1/chat/completions"));
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
