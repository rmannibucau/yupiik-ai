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
public record CompactionConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically compact the context when the threshold is reached.")
        boolean enabled,

        @Property(
                defaultValue = "80",
                documentation = "Usable context window usage percentage triggering the compaction.")
        int thresholdPercent,

        @Property(
                defaultValue = "0",
                documentation =
                        "Token budget of the verbatim recent tail kept by compaction (0 = auto: 25% of the usable window clamped to [2000, 15000]). The tail is extended or reduced at whole conversation turns and never splits an assistant tool call from its results.")
        int tailBudgetTokens) {}
