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
package io.yupiik.ia.agent.core.tools;

import java.util.Map;

public interface EmbeddedTool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> schema();

    default boolean readonly() {
        return false;
    }

    /**
     * Whether the tool is currently usable. Unusable tools are hidden from the model
     * (wire tool list and description) but still executable with an explicit error,
     * so a misconfiguration surfaces clearly instead of silently vanishing.
     */
    default boolean usable() {
        return true;
    }

    ToolResult execute(Map<String, Object> args);

    /**
     * Context-aware execution: the default implementation delegates to
     * {@link #execute(Map)} so stateless tools need no change. Tools that touch
     * session-scoped services (questions, todos) override this method and read
     * them from the explicitly passed {@link ToolContext} - never from a
     * thread-local, so concurrent sessions cannot cross-talk.
     */
    default ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        return execute(args);
    }
}
