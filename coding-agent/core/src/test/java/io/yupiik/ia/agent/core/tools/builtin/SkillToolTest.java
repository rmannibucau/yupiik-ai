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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillToolTest {
    @TempDir
    private static Path testWorkdir;

    @TempDir
    Path temp;

    @Test
    void sharedToolLoadsSameNamedSkillsFromEachSessionRoot() throws IOException {
        final var first = temp.resolve("first");
        final var second = temp.resolve("second");
        skill(first.resolve(".opencode/skills"), "shared", "First instructions");
        skill(second.resolve(".opencode/skills"), "shared", "Second instructions");
        final var tool = tool(first, true);
        assertEquals(
                ToolResult.ok("First instructions"), tool.execute(Map.of("name", "shared"), ToolContext.empty(first)));
        assertEquals(
                ToolResult.ok("Second instructions"),
                tool.execute(Map.of("name", "shared"), ToolContext.empty(second)));
        assertEquals(
                ToolResult.ok("First instructions"), tool.execute(Map.of("name", "shared"), ToolContext.empty(first)));
    }

    @Test
    void missingSkillListsOnlySessionAndGlobalNames() throws IOException {
        final var first = temp.resolve("first");
        final var second = temp.resolve("second");
        skill(first.resolve(".opencode/skills"), "only-first", "First instructions");
        skill(second.resolve(".claude/skills"), "only-second", "Second instructions");
        skill(temp.resolve("storage/skills"), "global", "Global instructions");
        final var tool = tool(first, true);
        final var inSecond = tool.execute(Map.of("name", "only-first"), ToolContext.empty(second));
        assertFalse(inSecond.success());
        assertEquals("Skill not found: only-first. Available: only-second, global", inSecond.output());
        final var inFirst = tool.execute(Map.of("name", "only-second"), ToolContext.empty(first));
        assertFalse(inFirst.success());
        assertEquals("Skill not found: only-second. Available: only-first, global", inFirst.output());
    }

    @Test
    void explicitRootsNeverSelectTheConfiguredProjectAndGlobalSkillsRemainAvailable() throws IOException {
        final var first = temp.resolve("first");
        final var second = temp.resolve("second");
        skill(first.resolve(".yca/skills"), "local", "Local instructions");
        skill(temp.resolve("home/.agents/skills"), "global", "Global instructions");
        final var tool = tool(first, true);
        final var args = Map.<String, Object>of("name", "local");
        assertEquals(ToolResult.ok("Local instructions"), tool.execute(args, ToolContext.empty(first)));
        assertFalse(tool.execute(args, ToolContext.empty(second)).success());
        assertEquals(
                ToolResult.ok("Global instructions"),
                tool.execute(Map.of("name", "global"), ToolContext.empty(second)));
        assertEquals(ToolResult.ok("Local instructions"), tool.execute(args, ToolContext.empty(first)));
    }

    @Test
    void sharedDescriptionDoesNotDiscoverAnyProjectCatalog() throws IOException {
        final var first = temp.resolve("first");
        final var second = temp.resolve("second");
        skill(first.resolve(".opencode/skills"), "first-secret-skill", "First private description");
        skill(second.resolve(".opencode/skills"), "second-secret-skill", "Second private description");
        final var tool = tool(first, true);
        final var description = tool.description(temp);
        assertTrue(description.contains("session"));
        assertFalse(description.contains("first-secret-skill"));
        assertFalse(description.contains("First private description"));
        assertFalse(description.contains("<available_skills>"));
        tool.execute(Map.of("name", "second-secret-skill"), ToolContext.empty(second));
        assertEquals(description, tool.description(temp));
        assertEquals(description, tool(second, true).description(second));
    }

    @Test
    void disabledSkillsCannotBeLoadedFromAnyExplicitRoot() throws IOException {
        final var first = temp.resolve("first");
        skill(first.resolve(".opencode/skills"), "local", "Local instructions");
        skill(temp.resolve("storage/skills"), "global", "Global instructions");
        final var tool = tool(first, false);
        assertTrue(tool.description(temp).contains("No skills are currently enabled"));
        assertFalse(tool.description(temp).contains("<available_skills>"));
        for (final var name : new String[] {"local", "global"}) {
            final var args = Map.<String, Object>of("name", name);
            assertEquals(ToolResult.error("Skills are disabled."), tool.execute(args, ToolContext.empty(temp)));
            assertEquals(ToolResult.error("Skills are disabled."), tool.execute(args, ToolContext.empty(first)));
            assertEquals(
                    ToolResult.error("Skills are disabled."),
                    new SkillTool(null).execute(args, ToolContext.empty(first)));
        }
    }

    @Test
    void nameRemainsRequiredAndCannotEscapeSkillDirectories() throws IOException {
        final var first = temp.resolve("first");
        skill(first.resolve(".opencode/skills"), "local", "Local instructions");
        final var tool = tool(first, true);
        assertEquals(ToolResult.error("name is required"), tool.execute(Map.of(), ToolContext.empty(first)));
        assertEquals(
                ToolResult.error("name is required"), tool.execute(Map.of("name", "  "), ToolContext.empty(first)));
        assertFalse(tool.execute(Map.of("name", "../local"), ToolContext.empty(first))
                .success());
    }

    private SkillTool tool(final Path workingDir, final boolean enabled) {
        return new SkillTool(TestConfigs.codingAgent(Map.of(
                "yca.workingDir", workingDir.toString(),
                "yca.home", temp.resolve("home").toString(),
                "yca.storageHome", temp.resolve("storage").toString(),
                "yca.skills.enabled", Boolean.toString(enabled))));
    }

    private void skill(final Path directory, final String name, final String content) throws IOException {
        final var file = directory.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
