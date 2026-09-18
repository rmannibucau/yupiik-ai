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
public record PruningConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically prune the context (cheaper than compaction, applied first).")
        boolean enabled,

        @Property(defaultValue = "65", documentation = "Context window usage percentage triggering the pruning.")
        int thresholdPercent,

        @Property(defaultValue = "6", documentation = "Number of most recent messages never touched by the pruning.")
        int keepRecent,

        @Property(
                defaultValue = "80",
                documentation = "Maximum characters kept for a pruned tool output (head and tail).")
        int toolOutputMaxChars) {}
