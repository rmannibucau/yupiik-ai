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
public record MemoryConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically extract durable knowledge from conversations into memory files.")
        boolean enabled,

        @Property(
                defaultValue = "8000",
                documentation = "Maximum number of characters of memory injected in the system prompt.")
        int maxChars,

        @Property(
                defaultValue = "0.2",
                documentation =
                        "Fraction of the resolved system prompt budget (provider/model aware) used as the memory "
                                + "injection budget: the effective budget is min(maxChars, resolvedSystemPromptCap * maxRatio). "
                                + "0 disables the ratio (plain maxChars). When the resolved budget falls below 1000 characters "
                                + "the memory section is auto-disabled (and extraction is skipped) so a small context window "
                                + "is never consumed by low-value memory.")
        double maxRatio,

        @Property(
                defaultValue = "true",
                documentation =
                        "Periodically rewrite the project memory with an LLM call (compact model) when it grows past a "
                                + "size threshold: near-duplicates are merged, superseded and one-off facts dropped. A "
                                + "backup (MEMORY.md.bak) is kept and rewrites reducing the file by less than 20% are "
                                + "discarded.")
        boolean compactEnabled) {}
