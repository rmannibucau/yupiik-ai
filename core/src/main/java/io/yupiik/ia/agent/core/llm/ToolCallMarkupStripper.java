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
import java.util.regex.Pattern;

/**
 * Strips the native tool-call markup that in-band tool-calling models leak into the text
 * channel when a request declares no tools: DeepSeek-style DSML
 * ({@code <\uFF5CDSML\uFF5Ctool_calls> ... </\uFF5CDSML\uFF5Ctool_calls>}), its V3.1
 * predecessor ({@code <\uFF5Ctool\u2581calls\u2581begin\uFF5C> ... <\uFF5Ctool\u2581calls\u2581end\uFF5C>})
 * and the ASCII-mangled variants some gateways emit when the special tokens survive
 * undecoded ({@code <|DSML|tool_calls>}, {@code <|tool_calls|>}).
 *
 * <p>A block is cut from its opener to its closing marker; when the closer is missing
 * (truncated output) the cut runs to the end of the text, since models almost never write
 * useful prose after a tool call. Prose before and after a block is preserved, and nested
 * invokes are removed together with the outer block. Opening patterns run outermost first
 * so a corrupted stream (inner markers without the outer ones) still leaves no orphan
 * closers behind - the final sweep removes those as well.
 */
@ApplicationScoped
public class ToolCallMarkupStripper {
    /** Fullwidth vertical bar or its ASCII mangling. */
    private static final String PIPE = "[\uFF5C|]";
    /** Optional fullwidth vertical bar or its ASCII mangling. */
    private static final String MAYBE_PIPE = "[\uFF5C|]?";
    /** Lower one eighth block used by the V3.1 markers instead of underscores. */
    private static final String LOW_LINE = "\u2581";

    private static final Pattern DSML_TOOL_CALLS_BLOCK = Pattern.compile(
            "<" + PIPE + "DSML" + PIPE + "tool_calls[^>]*>"
                    + ".*?(?:</" + PIPE + "DSML" + PIPE + "tool_calls\\s*>"
                    + "|<" + PIPE + "DSML" + PIPE + "end_tools\\s*>"
                    + "|\\z)",
            Pattern.DOTALL);

    private static final Pattern DSML_INVOKE_BLOCK = Pattern.compile(
            "<" + PIPE + "DSML" + PIPE + "invoke\\b[^>]*>"
                    + ".*?(?:</" + PIPE + "DSML" + PIPE + "invoke\\s*>"
                    + "|</" + PIPE + "DSML" + PIPE + "tool_calls\\s*>"
                    + "|<" + PIPE + "DSML" + PIPE + "end_tools\\s*>"
                    + "|\\z)",
            Pattern.DOTALL);

    private static final Pattern LEGACY_TOOL_CALLS_BLOCK = Pattern.compile(
            "<" + MAYBE_PIPE + "tool" + LOW_LINE + "calls" + LOW_LINE + "begin" + MAYBE_PIPE + ">"
                    + ".*?(?:<" + MAYBE_PIPE + "tool" + LOW_LINE + "calls" + LOW_LINE + "end" + MAYBE_PIPE + ">"
                    + "|\\z)",
            Pattern.DOTALL);

    private static final Pattern LEGACY_TOOL_CALL_BLOCK = Pattern.compile(
            "<" + MAYBE_PIPE + "tool" + LOW_LINE + "call" + LOW_LINE + "begin" + MAYBE_PIPE + ">"
                    + ".*?(?:<" + MAYBE_PIPE + "tool" + LOW_LINE + "call" + LOW_LINE + "end" + MAYBE_PIPE + ">"
                    + "|\\z)",
            Pattern.DOTALL);

    private static final Pattern ASCII_TOOL_CALLS_BLOCK = Pattern.compile(
            "<" + PIPE + "tool_calls" + PIPE + ">" + ".*?(?:</" + PIPE + "tool_calls" + PIPE + ">" + "|\\z)",
            Pattern.DOTALL);

    /** Qwen/Hermes JSON-in-tag: cut a full {@code <tool_call>...</tool_call>} block. */
    private static final Pattern JSON_TAG_BLOCK = Pattern.compile("<tool_call>.*?</tool_call>", Pattern.DOTALL);

    /** GLM kv: cut a full {@code <tool_call>name<arg_key>...</arg_key>...</tool_call>} block. */
    private static final Pattern GLM_TAG_BLOCK = Pattern.compile("<tool_call>.*?</tool_call>", Pattern.DOTALL);

    private static final Pattern ORPHAN_CLOSERS =
            Pattern.compile("</" + MAYBE_PIPE + "DSML" + PIPE + "(?:tool_calls|invoke)\\s*>"
                    + "|<" + MAYBE_PIPE + "DSML" + PIPE + "end_tools\\s*>"
                    + "|</" + MAYBE_PIPE + "tool_calls" + MAYBE_PIPE + ">"
                    + "|<" + MAYBE_PIPE + "tool" + LOW_LINE + "calls" + LOW_LINE + "end" + MAYBE_PIPE + ">"
                    + "|<" + MAYBE_PIPE + "tool" + LOW_LINE + "call" + LOW_LINE + "end" + MAYBE_PIPE + ">"
                    + "|</tool_call>");

    private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

    /**
     * Whether the raw text carries any of the in-band tool-call block markers this stripper knows
     * (DSML, V3.1, ASCII-mangled, Qwen/Hermes or GLM). Structure-aware: probes the actual marker
     * tokens, not arbitrary prose substrings, and returns {@code false} for a {@code null}/empty
     * input and for plain text.
     */
    public boolean containsMarkup(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        // Same marker families as {@link #strip}: the DSML blocks all carry "DSML", the V3.1
        // markers the lower-one-eighth-block glyph, the ASCII-mangled form still keeps
        // "tool_calls", and the Qwen/Hermes/GLM forms all open with the literal "<tool_call>".
        return raw.indexOf("DSML") >= 0
                || raw.indexOf(LOW_LINE) >= 0
                || raw.indexOf("tool_calls") >= 0
                || raw.indexOf("<tool_call>") >= 0;
    }

    /**
     * Removes every tool-call block from the raw model output.
     * A {@code null} input becomes an empty string; clean text is returned unchanged.
     *
     * @param raw the model message content as received.
     * @return the text without any tool-call markup, surrounding prose preserved.
     */
    public String strip(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // Fast path: when no marker family is present at all, the regexes below are pure
        // overhead - the DSML blocks all carry "DSML", the V3.1 markers the lower-one
        // eighth block glyph, the ASCII-mangled form still keeps "tool_calls", and the
        // Qwen/Hermes/GLM forms all open with the literal "<tool_call>".
        if (raw.indexOf("DSML") < 0
                && raw.indexOf(LOW_LINE) < 0
                && raw.indexOf("tool_calls") < 0
                && raw.indexOf("<tool_call>") < 0) {
            return raw;
        }
        var text = raw;
        text = DSML_TOOL_CALLS_BLOCK.matcher(text).replaceAll("");
        text = DSML_INVOKE_BLOCK.matcher(text).replaceAll("");
        text = LEGACY_TOOL_CALLS_BLOCK.matcher(text).replaceAll("");
        text = LEGACY_TOOL_CALL_BLOCK.matcher(text).replaceAll("");
        text = ASCII_TOOL_CALLS_BLOCK.matcher(text).replaceAll("");
        text = JSON_TAG_BLOCK.matcher(text).replaceAll("");
        text = GLM_TAG_BLOCK.matcher(text).replaceAll("");
        text = ORPHAN_CLOSERS.matcher(text).replaceAll("");
        return BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
    }
}
