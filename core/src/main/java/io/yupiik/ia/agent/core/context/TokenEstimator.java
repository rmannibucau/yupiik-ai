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

/**
 * Heuristic token estimation used to decide when to compact/prune. It is deliberately a
 * conservative <em>upper</em> bound: dense JSON, short identifiers, base64 and punctuation-heavy
 * tool arguments tokenize at ~0.4-0.6 tokens/char and would otherwise be under-counted, which
 * makes the compaction thresholds fire too late (the dangerous direction). It therefore charges
 * code/JSON-heavy text at a higher per-char rate and applies an explicit safety margin.
 */
public final class TokenEstimator {
    private static final double PROSE_RATE = 0.30;
    private static final double DENSE_RATE = 0.50;
    private static final double SAFETY_MARGIN = 1.2;

    private TokenEstimator() {}

    public static int estimate(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var tokens = 0.0;
        var codeChars = 0;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '{' || c == '}' || c == '(' || c == ')' || c == '=' || c == ';' || c == '<' || c == '>' || c == '_'
                    || c == '"' || c == '[' || c == ']' || c == ',' || c == ':' || c == '+' || c == '-' || c == '/'
                    || c == '\\' || c == '|' || c == '&' || c == '!') {
                codeChars++;
            }
        }
        tokens += text.length() * (codeChars > 0 ? DENSE_RATE : PROSE_RATE);
        tokens *= SAFETY_MARGIN;
        return (int) Math.ceil(tokens);
    }
}
