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

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class LlmWireTest {
    @Test
    void wireNeverContainsTheSpeakerIdentity() {
        final var m = Message.withIdentity(Message.assistant("a", List.of()), "p", "m", null);
        final var wire = LlmWire.toLlmMessage(m);
        assertFalse(wire.extensions().containsKey(Message.IDENTITY_KEY), "identity must be stripped on the wire");
        // unrelated others keys are preserved
        final var withOther = new Message(
                "user",
                "x",
                null,
                null,
                null,
                java.util.Map.of("is_error", true, Message.IDENTITY_KEY, java.util.Map.of("provider", "p")));
        final var otherWire = LlmWire.toLlmMessage(withOther);
        assertFalse(otherWire.extensions().containsKey(Message.IDENTITY_KEY));
        assertFalse(otherWire.extensions().isEmpty());
    }
}
