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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolCallArgumentsRepairerTest {

    private JsonMapper mapper;
    private ToolCallArgumentsRepairer repairer;

    @BeforeAll
    void init() {
        mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
        repairer = new ToolCallArgumentsRepairer(mapper);
    }

    @AfterAll
    void close() {
        mapper.close();
    }

    @Test
    void validArgumentsAreUntouched() {
        final var args = "{\"path\":\"src/main/java\",\"line\":12}";
        assertEquals(args, repairer.repair(args, true, "qwen3.8-27b"));
        // pretty printed (whitespace between tokens) stays valid too
        final var pretty = "{\n  \"path\": \"a b\",\n  \"line\": 1\n}";
        assertEquals(pretty, repairer.repair(pretty, true, "qwen3.8-27b"));
    }

    @Test
    void escapesBareControlCharsInsideStrings() {
        final var raw = "{\"command\":\"echo a\nb\",\"cwd\":\"/tmp\"}";
        final var repaired = repairer.repair(raw, true, "qwen3.8-27b");
        assertEquals("{\"command\":\"echo a\\nb\",\"cwd\":\"/tmp\"}", repaired);
        // tab inside a string value
        final var rawTab = "{\"pattern\":\"a\tb\"}";
        assertEquals("{\"pattern\":\"a\\tb\"}", repairer.repair(rawTab, true, "qwen3.8-27b"));
        // the repaired payload must parse
        assertTrue(isParseable(repaired));
    }

    @Test
    void preservesStructuralWhitespaceBetweenTokens() {
        // newlines/tabs as JSON whitespace (not in strings) must not be escaped
        final var pretty = "{\n\t\"path\": \"x\"\n}";
        assertEquals(pretty, repairer.repair(pretty, true, "qwen3.8-27b"));
    }

    @Test
    void alreadyEscapedSequencesAreNotDoubleEscaped() {
        final var args = "{\"x\":\"a\\nb\"}"; // already escaped \n
        assertEquals(args, repairer.repair(args, true, "qwen3.8-27b"));
    }

    @Test
    void unparseableOrUnfixableIsLeftUntouched() {
        final var garbage = "{\"command\": \"uv run python -c \"\nimport jax\n";
        assertEquals(garbage, repairer.repair(garbage, true, "qwen3.8-27b"));
        // disabled mode never touches anything
        final var raw = "{\"command\":\"echo a\nb\"}";
        assertEquals(raw, repairer.repair(raw, false, "qwen3.8-27b"));
    }

    @Test
    void nullAndBlankAreUntouched() {
        assertEquals(null, repairer.repair(null, true, "qwen3.8-27b"));
        assertEquals("", repairer.repair("", true, "qwen3.8-27b"));
    }

    @Test
    void scanDetectsBareControlChars() {
        assertTrue(ToolCallArgumentsRepairer.hasBareControlChar("{\"a\":\"b\nc\"}"));
        assertFalse(ToolCallArgumentsRepairer.hasBareControlChar("{\"a\":\"b\\nc\"}")); // escaped
        assertFalse(ToolCallArgumentsRepairer.hasBareControlChar("{\n\"a\": \"b\"\n}")); // whitespace
        assertFalse(ToolCallArgumentsRepairer.hasBareControlChar("{\"a\":\"b\"}"));
    }

    private boolean isParseable(final String json) {
        try (final var reader = new StringReader(json)) {
            mapper.read(java.util.Map.class, reader);
            return true;
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
