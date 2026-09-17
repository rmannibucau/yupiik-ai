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
package io.yupiik.ia.agent.core.cli.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DefaultPromptImplTest {

    @Test
    void readsLinesAndEchoesThePrompt() throws Exception {
        final var in = new ByteArrayInputStream("hello\nworld\n".getBytes(StandardCharsets.UTF_8));
        final var out = new ByteArrayOutputStream();
        final var prompt = new DefaultPromptImpl(in, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals("hello", prompt.readLine("> "));
        assertEquals("world", prompt.readLine("> "));
        assertNull(prompt.readLine("> "));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("> "));
        assertFalse(prompt.editing());
    }
}
