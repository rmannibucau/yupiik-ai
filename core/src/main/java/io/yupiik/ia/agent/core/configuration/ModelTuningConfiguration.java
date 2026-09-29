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
                defaultValue = "io.yupiik.ia.agent.core.configuration.RepairToolCallArgumentsMode.AUTO",
                documentation =
                        "Whether tool call arguments emitted by this model are repaired (best effort) before being exposed and stored. AUTO repairs for any Qwen 27B variant (the effective model contains 'qwen' and '27b', case-insensitive) and is disabled otherwise; ENABLED always repairs; DISABLED never repairs. This fixes llama.cpp/ollama style backends that reject arguments carrying unescaped control characters or a truncated tail with an HTTP 500 (\"Failed to parse tool call arguments as JSON\") under a heavy context. The provider-level tuning value is used when this per-model facet is absent or AUTO.")
        RepairToolCallArgumentsMode repairToolCallArguments,

        @Property(
                documentation =
                        "Default output token budget override for this model (put-if-absent too). Unset or zero/negative keeps the provider outputTokens default.")
        Integer outputTokens,

        @Property(
                documentation =
                        "Default system prompt maximum character budget override for this model (put-if-absent too). Unset or zero/negative keeps the provider tuning, then the global llm default, then a value derived from the provider context window (big windows keep ~32k, small windows shrink).")
        Integer systemPromptMaxChars,

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
