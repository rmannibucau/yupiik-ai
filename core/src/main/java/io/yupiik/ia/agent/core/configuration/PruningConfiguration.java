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

        @Property(defaultValue = "65", documentation = "Usable context window usage percentage triggering the pruning.")
        int thresholdPercent,

        @Property(
                defaultValue = "6",
                documentation =
                        "Minimum number of most recent messages never touched by the pruning (floor: the protected zone extends to whole conversation turns).")
        int keepRecent,

        @Property(
                defaultValue = "2",
                documentation =
                        "Most recent conversation turns (from a user prompt to the next one) never touched by the pruning.")
        int protectTurns,

        @Property(
                defaultValue = "40000",
                documentation =
                        "Estimated tokens of the most recent tool outputs protected from pruning, measured backwards from the protected turn boundary.")
        int protectRecentTokens,

        @Property(
                defaultValue = "20000",
                documentation =
                        "Minimum estimated tokens of old tool outputs required before pruning rewrites anything (avoids churn on small gains).")
        int minPruneTokens,

        @Property(
                defaultValue = "80",
                documentation =
                        "Character cap (head and tail) used by the local truncation fallback applied to old messages when the compaction summarizer fails.")
        int toolOutputMaxChars) {}
