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
package io.yupiik.llm.client.openai.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.Map;

/**
 * One input item of the {@code /responses} endpoint: a message, a function call or a function call output.
 *
 * @param type item type: {@code message}, {@code function_call} or {@code function_call_output}.
 * @param role message role, for {@code message} items.
 * @param content textual or structured content, for {@code message} items.
 * @param name optional participant name.
 * @param callId function call identifier, for function call items.
 * @param output JSON function result, for {@code function_call_output} items.
 * @param arguments JSON function arguments, for {@code function_call} items.
 * @param id item identifier.
 * @param status completion status of a {@code function_call_output} item: {@code completed} or
 *     {@code incomplete} (for a failed tool call).
 * @param others extra wire fields (e.g. {@code name} of a function call).
 */
@JsonModel
public record ResponsesInput(
        String type,
        String role,
        Object content,
        String name,
        @JsonProperty("call_id") String callId,
        @JsonProperty("output") String output,
        @JsonProperty("arguments") String arguments,
        @JsonProperty("id") String id,
        String status,
        @JsonOthers Map<String, Object> others) {

    /**
     * Creates a plain text message input.
     *
     * @param role message role.
     * @param text message content.
     * @return the input item.
     */
    public static ResponsesInput message(final String role, final String text) {
        return new ResponsesInput("message", role, text, null, null, null, null, null, null, null);
    }

    /**
     * Creates a function call input item, replaying a call the model made.
     *
     * @param callId function call identifier.
     * @param name function name.
     * @param arguments JSON function arguments.
     * @return the input item.
     */
    public static ResponsesInput functionCall(final String callId, final String name, final String arguments) {
        return new ResponsesInput(
                "function_call", null, null, null, callId, null, arguments, null, null, Map.of("name", name));
    }

    /**
     * Creates a function call output input item, answering a call the model made.
     *
     * @param callId function call identifier.
     * @param output JSON function result.
     * @return the input item.
     */
    public static ResponsesInput functionCallOutput(final String callId, final String output) {
        return functionCallOutput(callId, output, false);
    }

    /**
     * Creates a function call output input item, answering a call the model made.
     *
     * @param callId function call identifier.
     * @param output JSON function result.
     * @param isError whether the tool call failed (emitted as the standard responses {@code status}
     *     {@code incomplete}, never as a custom field).
     * @return the input item.
     */
    public static ResponsesInput functionCallOutput(final String callId, final String output, final boolean isError) {
        return new ResponsesInput(
                "function_call_output",
                null,
                null,
                null,
                callId,
                output,
                null,
                null,
                isError ? "incomplete" : null,
                null);
    }

    /**
     * Creates a message input carrying structured content.
     *
     * @param role message role.
     * @param content structured content parts.
     * @return the input item.
     */
    public static ResponsesInput messageContent(final String role, final Object content) {
        return new ResponsesInput("message", role, content, null, null, null, null, null, null, null);
    }
}
