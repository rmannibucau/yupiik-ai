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
package io.yupiik.ia.agent.core.tools;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.mcp.McpManager;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ToolRegistry {
    private final McpManager mcpManager;

    public ToolRegistry(final McpManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    protected ToolRegistry() {
        this.mcpManager = null;
    }

    public void onStart(@OnEvent final Start start) {
        mcpManager.start();
    }

    public List<EmbeddedTool> tools() {
        return mcpManager.tools().stream()
                .sorted(Comparator.comparing(EmbeddedTool::name))
                .toList();
    }

    public EmbeddedTool tool(final String name) {
        return tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    public List<Tool> toWireTools() {
        return tools().stream()
                .map(t -> Tool.function(t.name(), t.description(), t.schema()))
                .toList();
    }

    public String describe() {
        final var sb = new StringBuilder();
        for (final var t : tools()) {
            sb.append("- ")
                    .append(t.name())
                    .append(": ")
                    .append(t.description())
                    .append('\n');
        }
        return sb.toString();
    }
}
