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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolPresenterTest {
    private final ToolPresenter presenter = new ToolPresenter(new JsonMapperImpl(List.of(), key -> Optional.empty()));

    @Test
    void summarizesBashCommand() {
        assertEquals("Bash(mvn -q test)", presenter.summarize("bash", "{\"command\":\"mvn -q test\"}"));
    }

    @Test
    void summarizesBashWithWorkdir() {
        assertEquals("Bash(ls · /tmp)", presenter.summarize("bash", "{\"command\":\"ls\",\"workdir\":\"/tmp\"}"));
    }

    @Test
    void summarizesFileTools() {
        assertEquals(
                "Read(src/Foo.java: 500 lines)",
                presenter.summarize("read_file", "{\"path\":\"src/Foo.java\",\"limit\":500}"));
        assertEquals("Write(pom.xml)", presenter.summarize("write_file", "{\"path\":\"pom.xml\",\"content\":\"x\"}"));
        assertEquals(
                "Edit(pom.xml)",
                presenter.summarize("edit_file", "{\"path\":\"pom.xml\",\"oldString\":\"a\",\"newString\":\"b\"}"));
    }

    @Test
    void summarizesSearchTools() {
        assertEquals("Search(Foo in src)", presenter.summarize("grep", "{\"pattern\":\"Foo\",\"path\":\"src\"}"));
        assertEquals("Find(**/*.java)", presenter.summarize("glob", "{\"pattern\":\"**/*.java\"}"));
        assertEquals("List(src/main)", presenter.summarize("list_dir", "{\"path\":\"src/main\"}"));
    }

    @Test
    void summarizesTodosAndUnknown() {
        assertEquals(
                "Todos(2)", presenter.summarize("write_todo", "{\"todos\":[{\"content\":\"a\"},{\"content\":\"b\"}]}"));
        final var unknown = presenter.summarize("some_mcp_tool", "{\"query\":\"hello world\"}");
        assertTrue(unknown.startsWith("some_mcp_tool("));
    }

    @Test
    void editDiffShowsAddAndRemove() {
        final var diff = presenter.diff(
                "edit_file", "{\"path\":\"a\",\"oldString\":\"old line\",\"newString\":\"new line\"}", 5);
        assertEquals(List.of("- old line", "+ new line"), diff);
    }

    @Test
    void editDiffTruncatesLong() {
        final var diff = presenter.diff(
                "edit_file", "{\"path\":\"a\",\"oldString\":\"l1\\nl2\\nl3\\nl4\\nl5\\nl6\",\"newString\":\"\"}", 5);
        assertEquals(6, diff.size());
        assertTrue(diff.get(5).contains("more"));
    }

    @Test
    void writeDiffCapsAtMaxLines() {
        final var diff = presenter.diff("write_file", "{\"path\":\"a\",\"content\":\"1\\n2\\n3\\n4\\n5\\n6\\n7\"}", 3);
        assertEquals(List.of("+ 1", "+ 2", "+ 3", "... +4 more"), diff);
    }

    @Test
    void nonEditToolsHaveNoDiff() {
        assertTrue(presenter.diff("bash", "{\"command\":\"ls\"}", 5).isEmpty());
    }

    @Test
    void resultDigestPicksTailOnSuccess() {
        assertEquals("BUILD SUCCESS", presenter.result("bash", "line1\nBUILD SUCCESS\n    \n", true));
    }

    @Test
    void resultDigestPicksHeadOnFailure() {
        assertEquals("Error: boom", presenter.result("bash", "Error: boom\ntrace\ntrace", false));
    }

    @Test
    void resultDigestHandlesBlank() {
        assertEquals("done", presenter.result("bash", "   ", true));
        assertEquals("failed", presenter.result("bash", "", false));
    }

    @Test
    void malformedArgsDoNotThrow() {
        assertEquals("Read(?)", presenter.summarize("read_file", "{not json"));
        assertTrue(presenter.diff("edit_file", "{not json}", 5).isEmpty());
    }
}
