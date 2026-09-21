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

/**
 * Tuning defaults of a model (or of a provider when used at the provider level). When a facet is
 * unset the value falls back to the provider default, then to the implicit per-family scheme.
 * Highly inspired from the {code tuning} concept of opencode.
 */
@JsonModel
public record ModelTuningConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Default reasoning effort hint for this model (eg. low, medium or high). Applied only when neither the request nor the providerSpecificOptions already carries one (put-if-absent). Blank is ignored.")
        String reasoningEffort,

        @Property(
                documentation =
                        "Default output token budget override for this model (put-if-absent too). Unset or zero/negative keeps the provider outputTokens default.")
        Integer outputTokens,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Named tuning presets (variants) selectable at runtime via the /model command. Unset defers to the provider-level variants, then to the implicit per-family scheme.")
        List<ModelVariantConfiguration> variants,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional raw JSON object merged (put-if-absent) into the payload of every request for this model, over the provider-level one. Empty is ignored.")
        String providerSpecificOptions) {}
