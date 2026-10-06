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
public record ModelConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "Model used to compact the context. Defaults to the provider model when empty.")
        String compact,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Model used to prune the context. When empty the compact model is used, then the provider model when both are empty.")
        String prune,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Model used for the memory write path (extraction and rewrite). When empty the compact model is used, then the provider model.")
        String memory) {

    /** Falls back to the compact model when prune is empty. */
    public String resolvedPrune() {
        return prune == null || prune.isBlank() ? compact : prune;
    }

    /** Falls back to the compact model when memory is empty, then to the provider model (blank = provider default). */
    public String resolvedMemory() {
        return memory == null || memory.isBlank() ? compact : memory;
    }
}
