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
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the in-band tool-call markup that some models emit through the text channel even
 * when structured tool calls are declared - DeepSeek-style DSML
 * ({@code <\uFF5CDSML\uFF5Ctool_calls> ... </\uFF5CDSML\uFF5Ctool_calls>}), its V3.1
 * predecessor ({@code <\uFF5Ctool\u2581calls\u2581begin\uFF5C> ...}) and the ASCII
 * manglings ({@code <|DSML|tool_calls>}, {@code <|tool_calls|>}).
 *
 * <p>Each {@code <...invoke name=...>} block yields one {@link ToolCall} with a synthesized
 * id ({@code btw-N}) and its parameters marshalled to a JSON object string, so the same
 * execution path can handle native and markup tool calls. When there is no markup the
 * result is empty and the raw text can be used as-is.
 */
@ApplicationScoped
public class ToolCallMarkupParser {
    /** Fullwidth vertical bar or its ASCII mangling. */
    private static final String PIPE = "[\uFF5C|]";
    /** Optional fullwidth vertical bar or its ASCII mangling. */
    private static final String MAYBE_PIPE = "[\uFF5C|]?";
    /** Lower one eighth block used by the V3.1 markers instead of underscores. */
    private static final String LOW_LINE = "\u2581";

    private static final Pattern DSML_TOOL_CALLS = Pattern.compile(
            "<" + PIPE + "DSML" + PIPE + "tool_calls[^>]*>"
                    + "(.*?)(?:</" + PIPE + "DSML" + PIPE + "tool_calls\\s*>"
                    + "|<" + PIPE + "DSML" + PIPE + "end_tools\\s*>|\\z)",
            Pattern.DOTALL);

    private static final Pattern LEGACY_TOOL_CALLS = Pattern.compile(
            "<" + MAYBE_PIPE + "tool" + LOW_LINE + "calls" + LOW_LINE + "begin" + MAYBE_PIPE + ">"
                    + "(.*?)(?:<" + MAYBE_PIPE + "tool" + LOW_LINE + "calls" + LOW_LINE + "end" + MAYBE_PIPE
                    + ">|\\z)",
            Pattern.DOTALL);

    private static final Pattern ASCII_TOOL_CALLS = Pattern.compile(
            "<" + PIPE + "tool_calls" + PIPE + ">(.*?)(?:</" + PIPE + "tool_calls" + PIPE + ">|\\z)", Pattern.DOTALL);

    private static final Pattern INVOKE = Pattern.compile(
            "<" + PIPE + "DSML" + PIPE + "invoke\\b([^>]*)>(.*?)(?:</" + PIPE + "DSML" + PIPE + "invoke\\s*>|\\z)",
            Pattern.DOTALL);

    private static final Pattern LEGACY_INVOKE = Pattern.compile(
            "<" + MAYBE_PIPE + "tool" + LOW_LINE + "call" + LOW_LINE + "begin" + MAYBE_PIPE + ">"
                    + "(.*?)(?:<" + MAYBE_PIPE + "tool" + LOW_LINE + "call" + LOW_LINE + "end" + MAYBE_PIPE
                    + ">|\\z)",
            Pattern.DOTALL);

    private static final Pattern ATTR_NAME = Pattern.compile("\\bname\\s*=\\s*\"?([^\\s>\"]+)\"?");
    private static final Pattern PARAM = Pattern.compile(
            "<" + PIPE + "DSML" + PIPE + "parameter\\b([^>]*)>" + "(.*?)</" + PIPE + "DSML" + PIPE + "parameter\\s*>",
            Pattern.DOTALL);

    /**
     * Extracts the markup tool calls from the raw model output.
     *
     * @param raw the model message content as received, may be {@code null}.
     * @return the parsed tool calls, empty when no markup is present.
     */
    public List<ToolCall> parse(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        final var calls = new ArrayList<ToolCall>();
        int id = 0;
        for (final var block : blocks(raw)) {
            final var invokes = new ArrayList<String[]>();
            for (final var m = INVOKE.matcher(block); m.find(); ) {
                invokes.add(new String[] {m.group(1), m.group(2)});
            }
            if (invokes.isEmpty()) {
                for (final var m = LEGACY_INVOKE.matcher(block); m.find(); ) {
                    invokes.add(new String[] {"", m.group(1)});
                }
            }
            for (final var invoke : invokes) {
                final var attrs = invoke[0];
                final var body = invoke[1];
                final var nameMatcher = ATTR_NAME.matcher(attrs);
                final var name = nameMatcher.find() ? nameMatcher.group(1).strip() : legacyName(body);
                if (name == null || name.isBlank()) {
                    continue;
                }
                final var args = new LinkedHashMap<String, Object>();
                final var params = PARAM.matcher(body);
                while (params.find()) {
                    final var paramAttrs = params.group(1);
                    final var paramNameMatcher = ATTR_NAME.matcher(paramAttrs);
                    if (!paramNameMatcher.find()) {
                        continue;
                    }
                    final var paramName = paramNameMatcher.group(1).strip();
                    final var isString = paramAttrs.contains("string=true");
                    args.put(
                            paramName,
                            isString
                                    ? params.group(2).strip()
                                    : jsonValue(params.group(2).strip()));
                }
                calls.add(new ToolCall("btw-" + (++id), "function", new FunctionCall(name, json(args)), null));
            }
        }
        return calls;
    }

    private List<String> blocks(final String raw) {
        final var out = new ArrayList<String>();
        forEach(DSML_TOOL_CALLS, raw, out);
        forEach(LEGACY_TOOL_CALLS, raw, out);
        forEach(ASCII_TOOL_CALLS, raw, out);
        return out;
    }

    /**
     * The V3.1 era carried the tool name as a leading {@code name=...} line inside the call
     * block rather than in the invoke attributes.
     */
    private String legacyName(final String body) {
        final var m = ATTR_NAME.matcher(body);
        return m.find() ? m.group(1).strip() : null;
    }

    private void forEach(final Pattern pattern, final String raw, final List<String> out) {
        final Matcher m = pattern.matcher(raw);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    private String json(final Map<String, Object> args) {
        final var sb = new StringBuilder("{");
        final var it = args.entrySet().iterator();
        while (it.hasNext()) {
            final var e = it.next();
            sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
            if (e.getValue() instanceof final Raw raw) {
                sb.append(raw.value());
            } else if (e.getValue() instanceof final String string) {
                sb.append('"').append(escape(string)).append('"');
            } else {
                sb.append(e.getValue());
            }
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * A non-string parameter carries a raw JSON value (number, boolean, null, nested object),
     * or a quoted string when quoting was requested: keep it as-is unless it is quoted.
     */
    private Object jsonValue(final String raw) {
        if (!raw.isEmpty() && (raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"')) {
            return raw.substring(1, raw.length() - 1);
        }
        return new Raw(raw);
    }

    private record Raw(String value) {}

    private String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
