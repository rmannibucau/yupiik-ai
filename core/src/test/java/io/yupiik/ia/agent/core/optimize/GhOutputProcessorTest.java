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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GhOutputProcessorTest {
    private final GhOutputProcessor processor = new GhOutputProcessor();

    @Test
    void supportsGhOnly() {
        assertTrue(processor.supports("gh pr list"));
        assertTrue(processor.supports("cd repo && gh issue view 12"));
        assertFalse(processor.supports("git status"));
    }

    @Test
    void dropsMarkdownNoiseButKeepsContent() {
        final var out = processor
                .process(
                        "gh issue view 12",
                        List.of(
                                "---",
                                "[![CI](https://img.shields.io/ci.svg)](https://x)",
                                "<!-- hidden comment -->",
                                "<p align=\"center\">",
                                "Some real description",
                                "</p>",
                                "***")
                                .stream())
                .toList();
        assertEquals(List.of("Some real description"), out);
    }

    @Test
    void blankLinesSurviveFilterOnlyWhenNotNoise() {
        final var out = processor
                .process("gh repo view", List.of("text", "", "more").stream())
                .toList();
        assertEquals(List.of("text", "", "more"), out);
    }
}
