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

import java.util.List;
import java.util.Map;

/**
 * A single conversation turn. {@code content} is either a plain string or a list of content
 * part maps (OpenAI-style {@code {"type":..., "text":...}} blocks), which keeps multimodal
 * inputs representable without polluting the neutral model.
 *
 * @param role "system" | "user" | "assistant" | "tool".
 * @param content textual or structured content.
 * @param name optional participant name.
 * @param toolCalls tool calls emitted by the assistant.
 * @param toolCallId id of the tool call this message answers.
 * @param extensions provider-specific fields.
 */
public record LlmMessage(
        String role,
        Object content,
        String name,
        List<LlmToolCall> toolCalls,
        String toolCallId,
        Map<String, Object> extensions) {

    /** Defaults {@code extensions} to an empty map when absent. */
    public LlmMessage {
        extensions = extensions == null ? Map.of() : extensions;
    }

    /**
     * A system message.
     *
     * @param content the message content.
     * @return the system message.
     */
    public static LlmMessage system(final String content) {
        return new LlmMessage("system", content, null, null, null, Map.of());
    }

    /**
     * A user message.
     *
     * @param content the message content.
     * @return the user message.
     */
    public static LlmMessage user(final String content) {
        return new LlmMessage("user", content, null, null, null, Map.of());
    }

    /**
     * An assistant message, optionally carrying the tool calls it emitted.
     *
     * @param content the message content.
     * @param toolCalls the emitted tool calls, may be {@code null}.
     * @return the assistant message.
     */
    public static LlmMessage assistant(final String content, final List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content, null, toolCalls, null, Map.of());
    }

    /**
     * A tool result message answering a tool call.
     *
     * @param toolCallId the id of the answered tool call.
     * @param content the tool result content.
     * @return the tool message.
     */
    public static LlmMessage tool(final String toolCallId, final String content) {
        return new LlmMessage("tool", content, null, null, toolCallId, Map.of());
    }
}
