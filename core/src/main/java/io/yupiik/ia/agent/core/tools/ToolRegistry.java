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
import io.yupiik.ia.agent.core.codegraph.CodeResolveTool;
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
        return toWireTools(t -> true);
    }

    /**
     * Like {@link #toWireTools()} but keeps only the tools matching {@code filter} once the
     * code-resolve pruning and visibility rules have been applied, before the final
     * {@code usable()} check. The {@code filter} therefore sees the same candidates the main
     * agent sees - restricted subsets such as the read-only tools a /btw answer may call -
     * without ever re-exposing a pruned tool.
     *
     * @param filter the subset predicate applied to the visible, non-pruned tools.
     * @return the wire tool declarations for the matching tools.
     */
    public List<Tool> toWireTools(final java.util.function.Predicate<EmbeddedTool> filter) {
        final var all = tools();
        // The merged code_resolve tool subsumes the granular structural/share tools. When it is registered we
        // expose only it (plus on-demand maven_knowledge_content); when registered but no data source is live we
        // hide code_resolve too (and the granular no-op tools), keeping the wire tool list lean. When the merged
        // tool is not present at all, the underlying tool list is left untouched.
        final var resolve = all.stream()
                .filter(t -> t instanceof CodeResolveTool)
                .findFirst()
                .map(CodeResolveTool.class::cast)
                .orElse(null);
        final var stream = resolve == null
                ? all.stream()
                : all.stream().filter(t -> {
                    if (resolve.usable()) {
                        return !CodeResolveTool.prunedRichToolNames().contains(t.name());
                    }
                    return !CodeResolveTool.prunedRichToolNames().contains(t.name()) && !(t instanceof CodeResolveTool);
                });
        return stream.filter(filter)
                .filter(EmbeddedTool::usable)
                .map(t -> Tool.function(t.name(), t.description(), t.schema()))
                .toList();
    }

    public String describe() {
        final var sb = new StringBuilder();
        for (final var t : tools()) {
            if (!t.usable()) {
                continue;
            }
            sb.append("- ")
                    .append(t.name())
                    .append(": ")
                    .append(t.description())
                    .append('\n');
        }
        return sb.toString();
    }
}
