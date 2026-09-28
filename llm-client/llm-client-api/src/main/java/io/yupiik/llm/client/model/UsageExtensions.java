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
package io.yupiik.llm.client.model;

import java.util.Map;

/**
 * Reads the non standard usage extensions emitted by some OpenAI compatible servers and gateways.
 * <p>
 * Neither the OpenAI nor the Anthropic payload carries any generation timing, but a number of servers
 * and proxies (LiteLLM, vLLM, OpenRouter, ...) publish their own. These helpers normalize the shapes
 * found in the wild to a millisecond duration so the server side throughput can be displayed next to
 * the client side measurement. Unknown or malformed values are simply ignored: the caller always falls
 * back to its own measurement.
 *
 * @see Usage#generationTimeMs()
 */
public final class UsageExtensions {
    private UsageExtensions() {
        // no-op.
    }

    /**
     * Extracts a provider side generation time.
     *
     * @param extensions the non standard fields of the usage payload, may be {@code null}.
     * @param completionTokens the completion token count of the same payload, used to convert a rate into a duration.
     * @return the generation time in milliseconds, {@code null} when the payload carries no usable timing.
     */
    public static Long generationTimeMs(final Map<String, Object> extensions, final Long completionTokens) {
        if (extensions == null || extensions.isEmpty()) {
            return null;
        }
        final var millis = number(extensions, "generation_time_ms", "generation_time_millis", "generation_ms");
        if (millis != null && millis > 0) {
            return round(millis);
        }
        // seconds, the most common gateway shape.
        final var seconds = number(extensions, "generation_time", "generation_time_seconds");
        if (seconds != null && seconds > 0) {
            return round(seconds * 1_000d);
        }
        // rate, converted back to a duration when the token count is known.
        final var perSecond = number(extensions, "tokens_per_second", "tps", "output_tokens_per_second");
        final var tokens = completionTokens == null ? 0d : completionTokens;
        if (perSecond != null && perSecond > 0 && tokens > 0) {
            return round(tokens * 1_000d / perSecond);
        }
        return null;
    }

    private static Double number(final Map<String, Object> extensions, final String... keys) {
        for (final var key : keys) {
            final var value = extensions.get(key);
            if (value instanceof final Number n) {
                return n.doubleValue();
            }
            if (value instanceof final String s && !s.isBlank()) {
                try {
                    return Double.parseDouble(s.strip());
                } catch (final NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Long round(final double value) {
        return Math.max(0L, Math.round(value));
    }
}
