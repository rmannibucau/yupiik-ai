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

/**
 * Token usage of a completion, provider-agnostic.
 *
 * @param promptTokens input tokens, may be {@code null} when the provider omits it.
 * @param completionTokens output tokens, may be {@code null} when the provider omits it.
 * @param totalTokens total tokens, may be {@code null} when the provider omits it.
 * @param cacheReadTokens prompt tokens served from a provider-side prompt cache, when reported.
 * @param cacheWriteTokens prompt tokens written to a provider-side prompt cache, when reported.
 */
public record Usage(
        Long promptTokens, Long completionTokens, Long totalTokens, Long cacheReadTokens, Long cacheWriteTokens) {

    public static final Usage EMPTY = new Usage(0L, 0L, 0L, 0L, 0L);

    public Usage(final Long promptTokens, final Long completionTokens, final Long totalTokens) {
        this(promptTokens, completionTokens, totalTokens, null, null);
    }

    /**
     * The prompt token count.
     *
     * @return the prompt token count, 0 when absent.
     */
    public long prompt() {
        return promptTokens == null ? 0 : promptTokens;
    }

    /**
     * The completion token count.
     *
     * @return the completion token count, 0 when absent.
     */
    public long completion() {
        return completionTokens == null ? 0 : completionTokens;
    }

    /**
     * The total token count.
     *
     * @return the total token count, 0 when absent.
     */
    public long total() {
        return totalTokens == null ? 0 : totalTokens;
    }

    /**
     * The prompt-cache read token count.
     *
     * @return the cache read token count, 0 when absent.
     */
    public long cacheRead() {
        return cacheReadTokens == null ? 0 : cacheReadTokens;
    }

    /**
     * The prompt-cache write token count.
     *
     * @return the cache write token count, 0 when absent.
     */
    public long cacheWrite() {
        return cacheWriteTokens == null ? 0 : cacheWriteTokens;
    }
}
