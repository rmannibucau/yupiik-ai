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
import io.yupiik.fusion.json.JsonMapper;
import java.io.StringReader;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Best-effort repair of tool call arguments emitted by the model before they are exposed and
 * stored. Some OpenAI compatible backends (llama.cpp / ollama style) reject whole turns with an
 * HTTP 500 when the generated arguments carry unescaped control characters (literal tabs /
 * newlines inside string values) - the "Failed to parse tool call arguments as JSON:
 * [json.exception.parse_error.101]" signature, typically under a heavy context - or a dangling
 * truncated tail. This helper sanitizes the payload conservatively:
 *
 * <ul>
 *   <li>arguments without raw control characters are returned untouched (zero cost path);</li>
 *   <li>raw 0x00-0x1F control characters are escaped ({@code \\uXXXX}) only inside string values
 *       (JSON whitespace between tokens is preserved) and the result validated by a strict parse;</li>
 *   <li>if the repaired payload still does not parse, the original is returned: we never invent
 *       values or drop arguments, a corrupted call must stay visible.</li>
 * </ul>
 */
@ApplicationScoped
public class ToolCallArgumentsRepairer {

    private final JsonMapper mapper;

    public ToolCallArgumentsRepairer(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Repair the arguments when {@code enabled} is true. Returns the (possibly repaired) arguments.
     *
     * @param arguments raw arguments string emitted by the model.
     * @param enabled   whether the repair is active for the calling model.
     * @param model     effective model name, used only for the FINE log.
     */
    public String repair(final String arguments, final boolean enabled, final String model) {
        if (arguments == null || arguments.isBlank() || !enabled || !hasBareControlChar(arguments)) {
            return arguments;
        }
        final var repaired = escapeControlChars(arguments);
        if (repaired.equals(arguments) || !isValidJson(mapper, repaired)) {
            // escaping did not change anything or the fix did not help: keep the original so the
            // corruption remains visible instead of silently changing the model intent
            return arguments;
        }
        LOGGER.fine(() ->
                "repaired tool call arguments for " + model + ": " + preview(arguments) + " -> " + preview(repaired));
        return repaired;
    }

    private static boolean isValidJson(final JsonMapper mapper, final String value) {
        try (final var reader = new StringReader(value)) {
            mapper.read(Map.class, reader);
            return true;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** Whether the payload contains a raw control character (0x00-0x1F) inside a string value. */
    static boolean hasBareControlChar(final String json) {
        var inString = false;
        for (int i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (inString && c == '\\') {
                i++; // skip the escaped char (a raw control char after a backslash is an escape)
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString && c < 0x20) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escape raw control characters (0x00-0x1F) found inside JSON string values. JSON whitespace
     * outside strings ({@code \t \n \r}) is preserved, so a structurally valid payload with pretty
     * printing stays valid. Already-escaped sequences (backslash + any char) are never touched.
     */
    static String escapeControlChars(final String json) {
        final var sb = new StringBuilder(json.length() + 16);
        walk(json, sb);
        return sb.toString();
    }

    private static void walk(final String json, final StringBuilder out) {
        var inString = false;
        for (int i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (inString && c == '\\') {
                // keep the escape and its escaped character as-is (never double-escape)
                out.append(c);
                if (i + 1 < json.length()) {
                    out.append(json.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                continue;
            }
            if (inString && c < 0x20) {
                appendEscape(out, c);
                continue;
            }
            out.append(c);
        }
    }

    private static void appendEscape(final StringBuilder sb, final char c) {
        switch (c) {
            case '\b' -> sb.append("\\b");
            case '\t' -> sb.append("\\t");
            case '\n' -> sb.append("\\n");
            case '\f' -> sb.append("\\f");
            case '\r' -> sb.append("\\r");
            default -> appendUnicodeEscape(sb, c);
        }
    }

    private static void appendUnicodeEscape(final StringBuilder sb, final char c) {
        sb.append("\\u");
        sb.append(HEX[(c >> 12) & 0xF]);
        sb.append(HEX[(c >> 8) & 0xF]);
        sb.append(HEX[(c >> 4) & 0xF]);
        sb.append(HEX[c & 0xF]);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String preview(final String value) {
        final var v = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return v.length() <= 80 ? v : v.substring(0, 77) + "...";
    }

    private static final Logger LOGGER = Logger.getLogger(ToolCallArgumentsRepairer.class.getName());
}
