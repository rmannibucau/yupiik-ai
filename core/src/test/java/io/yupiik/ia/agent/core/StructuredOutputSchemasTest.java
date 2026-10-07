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
package io.yupiik.ia.agent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.MemoryExtractor;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Structured-output schemas must stay valid for strict providers (Anthropic structured outputs in
 * particular): {@code additionalProperties} only applies to objects, so an array entry carrying it
 * makes Anthropic reject the whole request with a 400 {@code json was malformed}. These regressions
 * pin the two schemas that previously violated that rule.
 */
class StructuredOutputSchemasTest {

    @Test
    void factsSchemaHasNoArrayLevelAdditionalProperties() {
        final var schema = MemoryExtractor.factsSchema();
        final var facts = array(schema, "facts");
        assertNotNull(facts, "facts array expected");
        assertFalse(facts.containsKey("additionalProperties"), "array must not carry additionalProperties");
        final var item = asMap(facts.get("items"));
        assertNotNull(item, "array items expected");
        assertTrue(
                Boolean.FALSE.equals(item.get("additionalProperties")),
                "item objects must keep additionalProperties: false");
        // root object keeps its own additionalProperties: false
        assertTrue(Boolean.FALSE.equals(schema.get("additionalProperties")), "root must keep additionalProperties");
    }

    @Test
    void stepsSchemaHasNoArrayLevelAdditionalProperties() {
        final var schema = PlanTodoSeeder.stepsSchema();
        final var steps = array(schema, "steps");
        assertNotNull(steps, "steps array expected");
        assertFalse(steps.containsKey("additionalProperties"), "array must not carry additionalProperties");
        final var item = asMap(steps.get("items"));
        assertNotNull(item, "array items expected");
        assertTrue(
                Boolean.FALSE.equals(item.get("additionalProperties")),
                "item objects must keep additionalProperties: false");
        assertTrue(Boolean.FALSE.equals(schema.get("additionalProperties")), "root must keep additionalProperties");
    }

    private static Map<String, Object> array(final Map<String, Object> root, final String name) {
        final var properties = asMap(root.get("properties"));
        return properties == null ? null : asMap(properties.get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
