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
package io.yupiik.ia.agent.slack.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSlackContextConfigurationTest {
    @Test
    void defaultsDisableCapabilityAndDoNotSelectAChannel() {
        final var configuration =
                new AgentSlackContextConfiguration$FusionConfigurationFactory(Configuration.of(Map.of())).get();
        assertFalse(configuration.enabled());
        assertEquals("/attempt/storage/slack-context", configuration.directory());
        assertEquals("", configuration.channel());
        assertEquals(30, configuration.timeoutSeconds());
    }

    @Test
    void bindsOnlyDedicatedSlackContextKeys() {
        final var configuration = new AgentSlackContextConfiguration$FusionConfigurationFactory(Configuration.of(Map.of(
                        "slackContext.enabled", "true",
                        "slackContext.directory", "/trusted/mailbox",
                        "slackContext.channel", "C123",
                        "slackContext.timeoutSeconds", "15",
                        "slack.channel", "COTHER")))
                .get();
        assertEquals(new AgentSlackContextConfiguration(true, "/trusted/mailbox", "C123", 15), configuration);
    }
}
