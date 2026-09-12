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
package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.Map;

class LocalMcpTool implements EmbeddedTool {
    private final LocalMcpServer server;
    private final EmbeddedTool delegate;

    LocalMcpTool(final LocalMcpServer server, final EmbeddedTool delegate) {
        this.server = server;
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public ToolCategory category() {
        return delegate.category();
    }

    @Override
    public Map<String, Object> schema() {
        return delegate.schema();
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        try {
            return ToolResult.ok(server.callTool(delegate.name(), args));
        } catch (final RuntimeException e) {
            return ToolResult.error("Tool " + delegate.name() + " failed: " + e.getMessage());
        } catch (final Exception e) {
            return ToolResult.error("Tool " + delegate.name() + " failed: " + e.getMessage());
        }
    }
}
