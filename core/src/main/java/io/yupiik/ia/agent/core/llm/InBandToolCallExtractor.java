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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;

/**
 * Shared {@code (parse, strip)} entry point for in-band tool-call markup emitted through the text
 * {@code content} channel. It is the single implementation of the block set used by both the main
 * agent loop and the {@code /btw} side conversation, so the two never drift.
 *
 * <p>Stateless: the parser and stripper are pure over their string input, so this bean can be
 * shared per request with no session state.</p>
 */
@ApplicationScoped
public class InBandToolCallExtractor {

    private final ToolCallMarkupParser parser;
    private final ToolCallMarkupStripper stripper;

    public InBandToolCallExtractor(final ToolCallMarkupParser parser, final ToolCallMarkupStripper stripper) {
        this.parser = parser;
        this.stripper = stripper;
    }

    /**
     * Parses the in-band tool calls from the raw model content, or an empty list when there is no
     * markup at all (a clean prose answer stays unmodified).
     *
     * @param raw the assistant content as received, may be {@code null}.
     * @return the extracted tool calls, empty when no markup is present.
     */
    public List<ToolCall> parse(final String raw) {
        return parser.parse(raw);
    }

    /**
     * Removes every in-band tool-call block from the raw model content, preserving surrounding prose.
     *
     * @param raw the assistant content as received, may be {@code null}.
     * @return the content without any tool-call markup, surrounding prose preserved.
     */
    public String strip(final String raw) {
        return stripper.strip(raw);
    }
}
