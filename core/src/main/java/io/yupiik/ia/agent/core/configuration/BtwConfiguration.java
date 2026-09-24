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
public record BtwConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "Model used for /btw side questions. Defaults to the provider model when empty.")
        String model,

        @Property(defaultValue = "2000", documentation = "Maximum output tokens of a /btw answer.")
        int maxTokens,

        @Property(
                defaultValue = "200",
                documentation =
                        "Maximum number of conversation messages inherited by a /btw answer as context (the most recent ones are kept). 0 or negative keeps the whole history.")
        int maxHistoryMessages,

        @Property(
                defaultValue = "5",
                documentation =
                        "Number of previous /btw questions and answers replayed to the model so follow-up side questions stay coherent (memory only, never persisted). 0 disables the replay.")
        int recall) {

    public static final BtwConfiguration DEFAULT = new BtwConfiguration("", 2000, 200, 5);
}
