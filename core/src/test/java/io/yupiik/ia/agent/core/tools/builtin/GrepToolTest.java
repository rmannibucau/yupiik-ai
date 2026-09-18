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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolTest {
    private final GrepTool tool = new GrepTool();

    @TempDir
    Path root;

    @Test
    void skipsIgnoredDirsAndHonorsGitignore() throws IOException {
        write(root.resolve(".gitignore"), "generated/\n");
        write(root.resolve("src/App.java"), "needle here\n");
        write(root.resolve("generated/Gen.java"), "needle here\n");
        write(root.resolve(".yca/generated/graph/wiring.json"), "needle here\n");
        write(root.resolve("target/Class.class"), "needle here\n");

        final var out = tool.execute(Map.of("pattern", "needle", "path", root.toString()))
                .output();

        assertTrue(out.contains("App.java"));
        assertFalse(out.contains("Gen.java"), "gitignored paths must be skipped:\n" + out);
        assertFalse(out.contains(".yca"), "yca store must be skipped:\n" + out);
        assertFalse(out.contains("target"), "target must be skipped:\n" + out);
    }

    @Test
    void capsResultsWithMarker() throws IOException {
        for (var i = 0; i < 10; i++) {
            write(root.resolve("F" + i + ".txt"), "hit\n");
        }
        final var out = tool.execute(Map.of("pattern", "hit", "path", root.toString(), "maxResults", "3"))
                .output();
        assertTrue(out.contains("3 match(es)"), out);
        assertTrue(out.contains("results capped at 3"), out);
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
