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
public record ToolsConfiguration(
        @Property(documentation = "Embedded (built-in) tools settings.")
        BuiltinToolsConfiguration builtin,

        @Property(documentation = "Web search tool settings.")
        WebSearchConfiguration websearch,

        @Property(documentation = "manage_context tool settings (model-driven context management).")
        ManageContextToolConfiguration manageContext,

        @Property(
                defaultValue = "true",
                documentation =
                        "Run consecutive read-only tool calls of a single model reply concurrently on virtual threads"
                                + " (eg. parallel read_file/grep batches). State-changing tools stay sequential and the"
                                + " message order always follows the model's call order. Set to false for fully"
                                + " sequential tool execution.")
        boolean parallelReadonly,

        @Property(
                defaultValue = "false",
                documentation =
                        "When enabled, the code_resolve tool also appends its short Maven artifact-knowledge note"
                                + " (which dependency provides the symbol, read with maven_knowledge_content) when the"
                                + " symbol was found in the local code graph. Disabled by default: with graph hits the"
                                + " note is skipped to keep resolve output lean. Requires the Maven repository knowledge"
                                + " feature to be enabled.")
        boolean alwaysSuggestMavenKnowledge) {}
