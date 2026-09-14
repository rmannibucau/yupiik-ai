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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AgentLoopTest {

    @Test
    void unknownToolDiagnosticNamesOffendingToolAndPointsToTools() throws Exception {
        final var diagnostic = diagnostic("github_review");
        assertTrue(diagnostic.contains("\"github_review\""), "should name the offending tool");
        assertTrue(diagnostic.contains("not in this session's toolset"), "should explain why it is rejected");
        assertTrue(diagnostic.contains("/tools"), "should point at the way to list valid tools");
    }

    @Test
    void unknownToolDiagnosticIsStableText() throws Exception {
        assertEquals(
                "Unknown tool \"foo\": not in this session's toolset (built-in or enabled MCP)."
                        + " Run /tools to list the valid tools; only exact names from the request's tools field are accepted.",
                diagnostic("foo"));
    }

    private static String diagnostic(final String name) throws Exception {
        final Method m = AgentLoop.class.getDeclaredMethod("unknownToolDiagnostic", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name);
    }
}
