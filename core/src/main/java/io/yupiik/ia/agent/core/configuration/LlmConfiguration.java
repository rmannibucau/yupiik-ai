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

public record LlmConfiguration(
        @Property(defaultValue = "3", documentation = "Maximum retry attempts for 429/5xx and network errors.")
        int retriesMaxAttempts,

        @Property(
                defaultValue = "500L",
                documentation = "Base delay in milliseconds of the exponential backoff (with jitter).")
        long retriesBaseDelayMs,

        @Property(
                defaultValue = "0",
                documentation = "Client side requests per minute throttle. 0 disables the throttle.")
        int rateLimitRequestsPerMinute,

        @Property(defaultValue = "4", documentation = "Maximum number of concurrent in-flight LLM requests.")
        int maxConcurrent,

        @Property(
                defaultValue = "true",
                documentation =
                        "On a provider 'context length exceeded' error, compact the context and retry the request once.")
        boolean retryOnContextOverflow,

        @Property(
                defaultValue = "250",
                documentation =
                        "Maximum number of iterations (LLM round-trips) in a single turn; each response carrying tool calls consumes one. The turn is stopped with an error when exceeded.")
        int maxIterations,

        @Property(
                defaultValue = "true",
                documentation =
                        "When a reply ends truncated by the output token limit (finish_reason=length) without tool calls, automatically ask the model to continue instead of ending the turn (up to 3 continuations, then the turn is ended with an error notice).")
        boolean continueOnTruncation) {}
