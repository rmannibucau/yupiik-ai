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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ToolOutputLanguageTest {
    @Test
    void commandProgramWins() {
        assertEquals("python", ToolOutputLanguage.guess("42\n", "python3 -c 'print(42)'"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "pip install -r requirements.txt"));
        assertEquals("javascript", ToolOutputLanguage.guess("ok\n", "node app.js"));
        assertEquals("sql", ToolOutputLanguage.guess("ok\n", "sqlite3 db.sqlite < q.sql"));
    }

    /** The model runs everything through a shell, so the interpreter behind it is what counts. */
    @Test
    void shellWrapperIsFollowed() {
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "sh -c 'python3 script.py'"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "cd /tmp && python3 script.py"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "PYTHONUNBUFFERED=1 python3 script.py"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "/usr/bin/python3 script.py"));
    }

    /** cat/grep/sh only print: their output is not shell code, better no color than a wrong one. */
    @Test
    void printingCommandsStayUncolored() {
        assertNull(ToolOutputLanguage.guess("total 8\n", "ls -la"));
        assertNull(ToolOutputLanguage.guess("a\nb\n", "cat out.txt"));
        assertNull(ToolOutputLanguage.guess("[INFO] BUILD SUCCESS\n", "mvn -q test"));
    }

    @Test
    void shebangOfPrintedScript() {
        assertEquals("python", ToolOutputLanguage.guess("#!/usr/bin/env python3\nprint(1)\n", "cat x.py"));
        assertEquals("python", ToolOutputLanguage.guess("#!/usr/bin/python\nprint(1)\n", null));
        assertNull(ToolOutputLanguage.guess("#!/bin/awk -f x.awk\n{ print }\n", null));
    }

    @Test
    void pythonTracebackIsPython() {
        assertEquals(
                "python",
                ToolOutputLanguage.guess(
                        "Traceback (most recent call last):\n  File \"a.py\", line 3, in <module>\n    print(1/0)\nZeroDivisionError: division by zero",
                        null));
    }

    @Test
    void sqlStatementIsSql() {
        assertEquals("sql", ToolOutputLanguage.guess("SELECT id FROM users WHERE id = 1\n", null));
    }

    @Test
    void unambiguousFileExtension() {
        assertEquals("python", ToolOutputLanguage.guess("a.py:1:1: E\nb.py:2:1: E\n", null));
    }

    /** Mixed or one-off references must not paint a whole log. */
    @Test
    void ambiguousOrSingleReferencesStayUncolored() {
        assertNull(ToolOutputLanguage.guess("a.py:1\n", null));
        assertNull(ToolOutputLanguage.guess("a.py:1\nb.json:2\n", null));
        assertNull(ToolOutputLanguage.guess("see README.md\n", null));
    }

    @Test
    void keywordDensity() {
        assertEquals("python", ToolOutputLanguage.guess("def main():\n    import os\n    print('x')\n", null));
        assertEquals(
                "javascript",
                ToolOutputLanguage.guess("const a = 1 => 1;\nfunction f() { const b = 2 => 2; }\n", null));
    }

    @Test
    void plainLogsAreLeftAlone() {
        assertNull(ToolOutputLanguage.guess("2026-09-21 10:00:00 INFO boom\ndone", null));
        assertNull(ToolOutputLanguage.guess("", null));
        assertNull(ToolOutputLanguage.guess(null, "python3 x.py"));
    }
}
