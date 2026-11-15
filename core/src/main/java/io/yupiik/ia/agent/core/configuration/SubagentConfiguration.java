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
import java.util.List;

@JsonModel
public record SubagentConfiguration(
        @Property(defaultValue = "true", documentation = "Enable sub-agent tools (spawn_subagent, check_subagents).")
        boolean enabled,

        @Property(
                defaultValue = "4",
                documentation = "Maximum number of simultaneous sub-agent AgentLoops allowed (a process-local budget, "
                        + "independent of the provider's in-flight LLM budget enforced by the shared client). "
                        + "Cap is also bounded by the model provider's maxConcurrent. Sub-agents queue on the "
                        + "shared LLM client when their requests would exceed the provider's concurrency.")
        int maxConcurrent,

        @Property(
                defaultValue = "250",
                documentation =
                        "Maximum number of model round-trips a sub-agent can execute in one run. A per-spawn max_turns can lower this limit; provider/model iteration caps can lower it further. When unset, the global yca.llm.maxIterations applies.")
        Integer maxIterations,

        @Property(
                defaultValue = "false",
                documentation =
                        "Allow automatic sub-agent routing to alternative models. By default children stay on the live parent provider/model; explicitly requested alternatives require user approval.")
        boolean allowModelOverride,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Exact $provider/model routes automatically available when overrides are enabled. Empty imposes no inclusion restriction. Explicit alternatives outside this list require user approval.")
        List<String> includes,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Exact $provider/model hard denies for every sub-agent; cannot be overridden by provider settings or user approval.")
        List<String> excludes,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Default alternative model for sub-agents when overrides are enabled, subject to includes/excludes. Blank inherits the parent route.")
        String defaultModel) {
    public SubagentConfiguration {
        includes = includes == null ? List.of() : List.copyOf(includes);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }
}
