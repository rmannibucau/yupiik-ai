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

public record AgentConfiguration(
        @Property(
                required = true,
                documentation =
                        "Unique name used to select this agent (/agent <name>, --agent <name>, or a @<name> prompt prefix).")
        String name,

        @Property(
                required = true,
                documentation =
                        "Agent instructions: inline text, or a file path whose content is loaded (eg. ~/.yupiik/coding-agent/agents/reviewer.md). A value is treated as a file path when it expands to an existing regular file, otherwise it is used as inline text.")
        String prompt) {}
