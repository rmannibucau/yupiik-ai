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
package io.yupiik.llm.client;

/**
 * Resilience knobs applied by {@link LlmClient} around every upstream call.
 *
 * @param retriesMaxAttempts max attempts before giving up (default 3).
 * @param retriesBaseDelayMs base exponential backoff delay (default 500).
 * @param rateLimitRequestsPerMinute token-bucket cap per minute, 0 disables throttling.
 * @param maxConcurrent max in-flight requests (default 4).
 * @param retryOnContextOverflow whether a {@link ContextWindowExceededException} retry is attempted.
 * @param retryAfterEnabled whether a {@code retry-after=} delay found on a provider rate-limit error
 *     is honored (raised to from the computed backoff, capped at {@code retryAfterMaxWaitMs}, with a
 *     10s default wait on concurrency-limit errors that carry no header); default on.
 * @param retryAfterMaxWaitMs upper bound honored for a provider {@code retry-after} or
 *     concurrency-limit cooldown (default 5 minutes).
 */
public record LlmResilienceConfig(
        int retriesMaxAttempts,
        long retriesBaseDelayMs,
        int rateLimitRequestsPerMinute,
        int maxConcurrent,
        boolean retryOnContextOverflow,
        boolean retryAfterEnabled,
        long retryAfterMaxWaitMs) {

    /**
     * The default configuration.
     *
     * @return 3 attempts, 500 ms base delay, throttling off, 4 concurrent, context overflow retry on,
     *     retry-after honoring on capped at 5 minutes.
     */
    public static LlmResilienceConfig defaults() {
        return new LlmResilienceConfig(3, 500, 0, 4, true, true, 300_000L);
    }
}
