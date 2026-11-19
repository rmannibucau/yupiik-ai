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
package io.yupiik.ia.agent.core.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLocatorTest {
    @TempDir
    Path temp;

    @Test
    void isolatesNamesFilesDescriptorsAndCatalogsAcrossRoots() throws IOException {
        final var first = temp.resolve("first");
        final var second = temp.resolve("second");
        final var configuration = configuration(first);
        final var firstShared = skill(first.resolve(".opencode/skills"), "shared", "First description");
        final var secondShared = skill(second.resolve(".opencode/skills"), "shared", "Second description");
        skill(first.resolve(".yca/skills"), "only-first", "First only");
        skill(second.resolve(".claude/skills"), "only-second", "Second only");
        for (final var root : List.of(first, second, first)) {
            final var isFirst = root.equals(first);
            assertEquals(isFirst ? firstShared : secondShared, SkillLocator.find(configuration, "shared", root));
            assertNull(SkillLocator.find(configuration, isFirst ? "only-second" : "only-first", root));
            final var names = SkillLocator.skillNames(configuration, root);
            assertTrue(names.containsAll(List.of("shared", isFirst ? "only-first" : "only-second")));
            assertFalse(names.contains(isFirst ? "only-second" : "only-first"));
            final var descriptors = SkillLocator.availableSkills(configuration, root);
            assertTrue(descriptors.contains(
                    new SkillLocator.SkillDescriptor("shared", isFirst ? "First description" : "Second description")));
            final var catalog = SkillLocator.availableSkillsCatalog(configuration, root);
            assertTrue(catalog.contains(isFirst ? "First description" : "Second description"));
            assertFalse(catalog.contains(isFirst ? "Second description" : "First description"));
            assertFalse(catalog.contains("private body"));
        }
    }

    @Test
    void requiresExplicitRootAndRetainsGlobalSkillDirectories() throws IOException {
        final var root = temp.resolve("project");
        final var configuration = configuration(root);
        final var local = skill(root.resolve(".yca/skills"), "local", "Local description");
        final var globalDirs = List.of(
                temp.resolve("storage/skills"),
                temp.resolve("home/.config/opencode/skills"),
                temp.resolve("home/.claude/skills"),
                temp.resolve("home/.agents/skills"));
        for (var i = 0; i < globalDirs.size(); i++) {
            final var name = "global-" + i;
            final var file = skill(globalDirs.get(i), name, "Global description " + i);
            assertEquals(file, SkillLocator.find(configuration, name, configuration.resolveWorkingDir()));
            assertEquals(file, SkillLocator.find(configuration, name, temp.resolve("other")));
        }
        assertEquals(local, SkillLocator.find(configuration, "local", configuration.resolveWorkingDir()));
        assertThrows(NullPointerException.class, () -> SkillLocator.find(configuration, "local", null));
        assertThrows(NullPointerException.class, () -> SkillLocator.skillDirectories(configuration, null));
        assertThrows(NullPointerException.class, () -> SkillLocator.availableSkillsCatalog(configuration, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillLocator.skillDirectories(configuration, Path.of("relative")));
        assertThrows(
                IllegalArgumentException.class, () -> SkillLocator.find(configuration, "local", Path.of("relative")));
        final var directories = SkillLocator.skillDirectories(configuration, temp.resolve("other"));
        assertEquals(globalDirs, directories.subList(directories.size() - 4, directories.size()));
    }

    @Test
    void preservesAncestorFirstAndDirectoryAndGlobalPrecedence() throws IOException {
        final var ancestor = temp.resolve("ancestor");
        final var nested = ancestor.resolve("nested");
        final var configuration = configuration(temp.resolve("unrelated"));
        final var winner = skill(ancestor.resolve(".opencode/skills"), "duplicate", "Ancestor opencode");
        skill(ancestor.resolve(".claude/skills"), "duplicate", "Ancestor claude");
        skill(ancestor.resolve(".yca/skills"), "duplicate", "Ancestor yca");
        skill(nested.resolve(".opencode/skills"), "duplicate", "Nested opencode");
        skill(temp.resolve("storage/skills"), "duplicate", "Global duplicate");
        assertEquals(winner, SkillLocator.find(configuration, "duplicate", nested));
        assertEquals(
                List.of(new SkillLocator.SkillDescriptor("duplicate", "Ancestor opencode")),
                SkillLocator.availableSkills(configuration, nested));
        final var expected = new ArrayList<Path>();
        final var walk = new ArrayList<Path>();
        for (var dir = nested; dir != null; dir = dir.getParent()) {
            walk.add(dir);
        }
        for (final var dir : walk.reversed()) {
            expected.add(dir.resolve(".opencode/skills"));
            expected.add(dir.resolve(".claude/skills"));
            expected.add(dir.resolve(".yca/skills"));
        }
        final var actual = SkillLocator.skillDirectories(configuration, nested.resolve("../nested"));
        assertEquals(expected, actual.subList(0, actual.size() - 4));
    }

    private CodingAgentConfiguration configuration(final Path root) {
        return TestConfigs.codingAgent(Map.of(
                "yca.workingDir", root.toString(),
                "yca.home", temp.resolve("home").toString(),
                "yca.storageHome", temp.resolve("storage").toString()));
    }

    private Path skill(final Path directory, final String name, final String description) throws IOException {
        final var file = directory.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: " + description + "\n---\nprivate body");
        return file;
    }
}
