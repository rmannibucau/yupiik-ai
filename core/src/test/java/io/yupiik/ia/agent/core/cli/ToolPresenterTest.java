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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ToolPresenterTest {
    private JsonMapper mapper;
    private ToolPresenter presenter;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        presenter = new ToolPresenter(mapper);
    }

    @Test
    void askUserResultRendersKeyValueLines() {
        assertEquals("q1: yes\nq2: a, b", presenter.result("ask_user", "{\"q1\":\"yes\",\"q2\":[\"a\",\"b\"]}", true));
    }

    @Test
    void malformedAskUserResultFallsBackToTruncation() {
        final var rendered = presenter.result("ask_user", "{oops", true);
        assertTrue(rendered.contains("{oops"));
    }

    @Test
    void summarizesBashCommand() {
        assertEquals("Bash(mvn -q test)", presenter.summarize("bash", "{\"command\":\"mvn -q test\"}"));
    }

    @Test
    void significantCommandKeepsSimpleCommand() {
        assertEquals("mvn -q test", ToolPresenter.significantCommand("mvn -q test"));
    }

    @Test
    void significantCommandSkipsNavigationPreamble() {
        assertEquals("ls -la", ToolPresenter.significantCommand("cd /foo && ls -la"));
        assertEquals("mvn -q test", ToolPresenter.significantCommand("cd /foo && cd bar && mvn -q test"));
        assertEquals("ls -la", ToolPresenter.significantCommand("export FOO=1; ls -la"));
    }

    @Test
    void significantCommandKeepsFirstRealSegment() {
        assertEquals("mvn test", ToolPresenter.significantCommand("mvn test && echo done"));
    }

    @Test
    void significantCommandDropsEnvAssignments() {
        assertEquals("mvn test", ToolPresenter.significantCommand("FOO=bar mvn test"));
        assertEquals("mvn test", ToolPresenter.significantCommand("FOO=\"a b\" BAR=2 mvn test"));
    }

    @Test
    void significantCommandIgnoresSeparatorsInsideQuotes() {
        assertEquals("echo \"a && b\"", ToolPresenter.significantCommand("echo \"a && b\""));
        assertEquals("ls -la", ToolPresenter.significantCommand("cd /a; ls -la"));
    }

    @Test
    void significantCommandFlattensLineContinuations() {
        assertEquals("mvn test", ToolPresenter.significantCommand("cd /w && \\\n  mvn test"));
    }

    @Test
    void significantCommandIsBlankSafe() {
        assertEquals("", ToolPresenter.significantCommand(null));
        assertEquals("", ToolPresenter.significantCommand("   \n  "));
    }

    @Test
    void summarizesBashWithWorkdir() {
        assertEquals("Bash(ls · build)", presenter.summarize("bash", "{\"command\":\"ls\",\"workdir\":\"build\"}"));
    }

    @Test
    void summarizesFileTools() {
        assertEquals(
                "Read(src/Foo.java: 500 lines)",
                presenter.summarize("read", "{\"path\":\"src/Foo.java\",\"limit\":500}"));
        assertEquals("Write(pom.xml)", presenter.summarize("write_file", "{\"path\":\"pom.xml\",\"content\":\"x\"}"));
        assertEquals(
                "Edit(pom.xml)",
                presenter.summarize("edit_file", "{\"path\":\"pom.xml\",\"oldString\":\"a\",\"newString\":\"b\"}"));
    }

    @Test
    void summarizesSearchTools() {
        assertEquals("Search(Foo in src)", presenter.summarize("grep", "{\"pattern\":\"Foo\",\"path\":\"src\"}"));
        assertEquals("Find(**/*.java)", presenter.summarize("glob", "{\"pattern\":\"**/*.java\"}"));
        assertEquals("Read(src/main)", presenter.summarize("read", "{\"path\":\"src/main\"}"));
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
        assertEquals("Read(?)", presenter.summarize("read", "{not json"));
        assertTrue(presenter.diff("edit_file", "{not json}", 5).isEmpty());
    }
}
