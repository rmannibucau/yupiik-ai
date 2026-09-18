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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TuiInputSupportTest {
    @Test
    void slashCommandsAreComplete() {
        assertEquals(24, TuiInputSupport.SLASH_COMMANDS.size());
        assertEquals("/help", TuiInputSupport.SLASH_COMMANDS.get(0));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/quit"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/exit"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/thinking"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/tool-output"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/session-id"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/session-flush"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/session-housekeeping"));
        assertTrue(TuiInputSupport.SLASH_COMMANDS.contains("/maven-repository-knowledge"));
        assertThrows(UnsupportedOperationException.class, () -> TuiInputSupport.SLASH_COMMANDS.add("/nope"));
    }

    @Test
    void nextWordStopsAtWordEnds() {
        assertEquals(3, TuiInputSupport.nextWord("foo bar baz", 0));
        assertEquals(7, TuiInputSupport.nextWord("foo bar baz", 4));
        assertEquals(11, TuiInputSupport.nextWord("foo bar baz", 8));
        assertEquals(3, TuiInputSupport.nextWord("foo", 0));
    }

    @Test
    void previousWordStopsAtWordStarts() {
        assertEquals(0, TuiInputSupport.previousWord("foo bar baz", 3));
        assertEquals(4, TuiInputSupport.previousWord("foo bar baz", 7));
        assertEquals(8, TuiInputSupport.previousWord("foo bar baz", 11));
        assertEquals(0, TuiInputSupport.previousWord("foo bar baz", 0));
    }

    @Test
    void firstLineExtractsAndCaps() {
        assertEquals("first", TuiInputSupport.firstLine("first\nsecond"));
        assertEquals("", TuiInputSupport.firstLine(null));
        assertEquals(123, TuiInputSupport.firstLine("x".repeat(500)).length());
        assertTrue(TuiInputSupport.firstLine("x".repeat(500)).endsWith("..."));
    }
}
