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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CodingAgentConfigurationTest {

    private static final String HOME = System.getProperty("user.home");

    @Test
    void mcpCallTimeoutDefaultsAndOverridesAreInstanceScoped() {
        final var defaults = TestConfigs.codingAgent(Map.of());
        final var overridden = TestConfigs.codingAgent(Map.of("yca.mcp.callTimeoutSeconds", "2"));
        assertEquals(60, defaults.mcp().callTimeoutSeconds());
        assertEquals(2, overridden.mcp().callTimeoutSeconds());
        assertEquals(60, TestConfigs.codingAgent(Map.of()).mcp().callTimeoutSeconds());
        assertEquals(60, defaults.mcp().callTimeoutSeconds());
    }

    @Test
    void expandHomeExpandsLeadingTildePath() {
        assertEquals(HOME + "/.m2/repository", CodingAgentConfiguration.expandHome("~/.m2/repository"));
    }

    @Test
    void expandHomeLeavesOtherValuesUntouched() {
        assertEquals("/opt/app/config", CodingAgentConfiguration.expandHome("/opt/app/config"));
        assertEquals("relative/path", CodingAgentConfiguration.expandHome("relative/path"));
        // a bare "~" (no trailing slash) is not expanded
        assertEquals("~", CodingAgentConfiguration.expandHome("~"));
        // null and blank pass through as-is
        assertNull(CodingAgentConfiguration.expandHome(null));
    }

    @Test
    void resolveStorageHomeAndHomeDelegateToExpandHome() {
        final var config = TestConfigs.codingAgent(Map.of());
        // default storage home carries the ~/ shorthand -> expanded to an absolute home-based path
        assertEquals(HOME + "/.yupiik/coding-agent", config.resolveStorageHome());
        // default home is the JVM user home
        assertEquals(HOME, config.resolveHome());
    }
}
