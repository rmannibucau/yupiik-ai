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

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.skills.SkillLocator;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;

@ApplicationScoped
public class SkillTool implements EmbeddedTool {
    private final CodingAgentConfiguration configuration;

    public SkillTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected SkillTool() {
        this.configuration = null;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Load a skill by name and return its SKILL.md instructions. Available skills are the directories containing a SKILL.md under .opencode/skills, .claude/skills or .yca/skills (searched up from the working directory) and the global skill directories.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of("name", Schemas.str("The skill name to load.")), "name");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var name = arg(args, "name");
        if (name == null || name.isBlank()) {
            return ToolResult.error("name is required");
        }
        final var file = SkillLocator.find(configuration, name);
        if (file == null) {
            return ToolResult.error("Skill not found: " + name + ". Available: "
                    + String.join(", ", SkillLocator.skillNames(configuration)));
        }
        try {
            return ToolResult.ok(Files.readString(file));
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot read skill " + name + ": " + e.getMessage());
        }
    }
}
