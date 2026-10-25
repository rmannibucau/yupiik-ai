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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.llm.ModelsResponse;

/**
 * Converts the context window reported by a provider into the input budget the agent can really use.
 *
 * <p>The window (configured total or detected from {@code /v1/models}) covers both the prompt and the
 * completion, so the completion budget has to be reserved from it. When the model declares an output
 * cap, that cap is what the next reply may consume and it is the value reserved: a 262144 window
 * declaring 32768 completion tokens leaves 229376 for the input. Without a declared cap - or when the
 * declared cap is not smaller than the window, hence cannot be honoured - a conservative quarter of the
 * window (capped at {@link #DEFAULT_OUTPUT_RESERVE} tokens) is reserved instead.</p>
 *
 * <p>When the output cap comes from a relative/estimated source (not an absolute hard limit), it is
 * treated as a hint and the conservative reserve applies instead of trusting it as a hard partition.</p>
 */
public final class ContextBudget {
    /** Output headroom reserved when the model does not declare a usable output cap. */
    public static final int DEFAULT_OUTPUT_RESERVE = 20_000;

    /** Floor of the usable window, so a tiny or unknown window never makes a request impossible. */
    public static final int MIN_USABLE_WINDOW = 1_024;

    private ContextBudget() {
        // utility class
    }

    /**
     * The completion headroom to reserve in a context window.
     *
     * @param window               the total context window in tokens (with absolute flag), or non-positive when unknown.
     * @param declaredOutputTokens the output cap declared by the model/provider (with absolute flag), or non-positive when unknown.
     * @return the declared cap when positive, absolute, and smaller than the window, else
     *         {@code min(20000, window / 4)}; {@code 0} for an unknown window.
     */
    public static int outputReserve(
            final ModelsResponse.TokenCount window, final ModelsResponse.TokenCount declaredOutputTokens) {
        final int w = window == null ? 0 : window.value();
        if (w <= 0) {
            return 0;
        }
        final int declared = declaredOutputTokens == null ? 0 : declaredOutputTokens.value();
        // Only trust absolute (hard limit) output caps that are smaller than the total window
        if (declared > 0 && declaredOutputTokens.absolute() && declared < w) {
            return declared;
        }
        return Math.min(DEFAULT_OUTPUT_RESERVE, w / 4);
    }

    /**
     * The window minus {@link #outputReserve(TokenCount, TokenCount)}, never below {@link #MIN_USABLE_WINDOW}.
     *
     * @param window               the total context window in tokens (with absolute flag), or non-positive when unknown.
     * @param declaredOutputTokens the output cap declared by the model/provider (with absolute flag), or non-positive when unknown.
     * @return the input budget the agent may fill without risking a window overflow.
     */
    public static int usableWindow(
            final ModelsResponse.TokenCount window, final ModelsResponse.TokenCount declaredOutputTokens) {
        final int w = window == null ? 0 : window.value();
        if (w <= 0) {
            return MIN_USABLE_WINDOW;
        }
        return Math.max(MIN_USABLE_WINDOW, w - outputReserve(window, declaredOutputTokens));
    }
}
