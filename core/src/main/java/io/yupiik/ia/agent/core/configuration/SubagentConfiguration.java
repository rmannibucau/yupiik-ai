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
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record SubagentConfiguration(
        @Property(defaultValue = "true", documentation = "Enable sub-agent tools (spawn_subagent, check_subagents).")
        boolean enabled,

        @Property(
                defaultValue = "4",
                documentation =
                        "Maximum concurrent sub-agents allowed globally. The actual limit is also bounded by the model provider's maxConcurrent minus one (reserved for the main agent).")
        int maxConcurrent,

        @Property(
                defaultValue = "250",
                documentation =
                        "Maximum number of iterations (LLM round-trips) a sub-agent can execute in a single turn. Defaults to the global yca.llm.maxIterations when not set.")
        Integer maxIterations) {}
