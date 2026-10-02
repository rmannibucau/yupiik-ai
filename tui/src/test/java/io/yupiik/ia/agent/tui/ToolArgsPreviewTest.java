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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ToolArgsPreviewTest {
    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private ToolArgsPreview preview() {
        return new ToolArgsPreview(new ToolOutputFormat(mapper), new SyntaxHighlighter(), new DiffRenderer());
    }

    @Test
    void writeFileRendersPathAndHighlightedContent() {
        final var lines = preview()
                .preview(
                        "write_file",
                        "{\"path\":\"src/main/java/Foo.java\",\"content\":\"class Foo {}\\n\"}",
                        80,
                        PaletteTest.palette());
        assertNotNull(lines);
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(s -> s.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(text.contains("src/main/java/Foo.java"), text);
        assertTrue(text.contains("class Foo"), text);
    }

    @Test
    void editFileRendersSyntheticDiff() {
        final var lines = preview()
                .preview(
                        "edit_file",
                        "{\"path\":\"README.md\",\"oldString\":\"old\",\"newString\":\"new\"}",
                        80,
                        PaletteTest.palette());
        assertNotNull(lines);
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(s -> s.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(text.contains("a/README.md"), text);
        assertTrue(text.contains(" - old"), text);
        assertTrue(text.contains(" + new"), text);
    }

    @Test
    void applyPatchRendersRawPatch() {
        final var lines = preview()
                .preview(
                        "apply_patch",
                        "{\"patch\":\"--- a/a.txt\\n+++ b/a.txt\\n@@ -1 +1 @@\\n-x\\n+y\\n\"}",
                        80,
                        PaletteTest.palette());
        assertNotNull(lines);
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(s -> s.content())
                .reduce("", (a, b) -> a + b);
        assertTrue(text.contains("a/a.txt"), text);
        assertTrue(text.contains(" - x"), text);
        assertTrue(text.contains(" + y"), text);
    }

    @Test
    void unknownToolOrBadJsonFallsBackToNull() {
        final var preview = preview();
        assertNull(preview.preview("bash", "{\"command\":\"ls\"}", 80, PaletteTest.palette()));
        assertNull(preview.preview("write_file", "not json", 80, PaletteTest.palette()));
        assertNull(preview.preview("write_file", null, 80, PaletteTest.palette()));
        assertNull(preview.preview("write_file", "{\"path\":\"a.txt\"}", 80, PaletteTest.palette()));
    }
}
