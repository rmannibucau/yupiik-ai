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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import java.util.List;

public record McpServerConfiguration(
        @Property(
                required = true,
                documentation = "Server name, used to prefix its tools (eg. context7_resolve-library-id).")
        String name,

        @Property(documentation = "Command to spawn for a stdio server (eg. npx). Mandatory when url is not set.")
        String command,

        @Property(
                documentation =
                        "Command arguments, comma separated in flat configuration, JSON array in configuration.json.")
        List<String> args,

        @Property(documentation = "Environment variables of the spawned process, KEY=VALUE entries.")
        List<String> env,

        @Property(documentation = "URL of a streamable HTTP server, alternative to command.")
        String url,

        @Property(documentation = "Optional API key sent as Authorization: Bearer header for HTTP servers.")
        String apiKey,

        @Property(documentation = "Extra HTTP headers for HTTP servers, KEY=VALUE entries.")
        List<String> headers,

        @Property(defaultValue = "true", documentation = "Enable this server.")
        boolean enabled,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Tool names of this server treated as read-only (the only MCP tools allowed in plan mode). `*` enables all tools of the server.")
        List<String> readonly) {

    public boolean isReadOnly(final String toolName) {
        return readonly != null && (readonly.contains("*") || readonly.contains(toolName));
    }
}
