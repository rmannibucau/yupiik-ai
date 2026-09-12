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

public record StreamDelta(Type type, String text, ToolCall toolCall, Usage usage, String finishReason) {

    public enum Type {
        TEXT,
        REASONING,
        TOOL_CALL,
        USAGE,
        FINISH
    }

    public static StreamDelta text(final String text) {
        return new StreamDelta(Type.TEXT, text, null, null, null);
    }

    public static StreamDelta reasoning(final String text) {
        return new StreamDelta(Type.REASONING, text, null, null, null);
    }

    public static StreamDelta toolCall(final ToolCall call) {
        return new StreamDelta(Type.TOOL_CALL, null, call, null, null);
    }

    public static StreamDelta usage(final Usage usage) {
        return new StreamDelta(Type.USAGE, null, null, usage, null);
    }

    public static StreamDelta finish(final String reason) {
        return new StreamDelta(Type.FINISH, null, null, null, reason);
    }
}
