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

/**
 * Per-provider overrides of the compaction resilience knobs. A null/non-positive field inherits the
 * global {@link CompactionConfiguration} / {@link LlmConfiguration} value, so an unset block keeps
 * the historical behavior.
 */
@JsonModel
public record ProviderCompactionConfiguration(
        @Property(
                defaultValue = "-1",
                documentation = "Per-provider maximum retry attempts for 429/5xx and network errors on the compaction "
                        + "summarizer path. Zero or negative (the default) falls back to the global "
                        + "yca.llm.retriesMaxAttempts.")
        int retriesMaxAttempts,

        @Property(
                defaultValue = "-1",
                documentation = "Per-provider base delay in milliseconds of the exponential backoff (with jitter) for "
                        + "compaction summarizer retries. Zero or negative (the default) falls back to the "
                        + "global yca.llm.retriesBaseDelayMs.")
        int retriesBaseDelayMs,

        @Property(
                defaultValue = "-1",
                documentation = "Per-provider client side requests per minute throttle for the compaction summarizer. "
                        + "Zero or negative (the default) falls back to the global "
                        + "yca.llm.rateLimitRequestsPerMinute.")
        int rateLimitRequestsPerMinute,

        @Property(
                documentation =
                        "Whether a compaction summarizer that exhausts all its retries on a transient 5xx/network "
                                + "failure falls back to the provider's main (conversation) model for one last "
                                + "attempt. Null (the default) inherits the global yca.compaction.fallbackToMainModel; "
                                + "true/false override it.")
        Boolean fallbackToMainModel,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider buffer-mode trigger safety cap (see yca.compaction.triggerSafetyPercent). "
                                + "Zero or negative (the default) inherits the global value.")
        int triggerSafetyPercent,

        @Property(
                defaultValue = "-1",
                documentation = "Per-provider compaction-gate attempt budget (see yca.compaction.gateMaxAttempts). "
                        + "Zero or negative (the default) inherits the global value.")
        int gateMaxAttempts,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider compaction-gate wall-clock budget in milliseconds "
                                + "(see yca.compaction.gateMaxDurationMs). Zero or negative (the default) inherits the global value.")
        int gateMaxDurationMs) {}
