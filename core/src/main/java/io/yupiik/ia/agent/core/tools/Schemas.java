package io.yupiik.ia.agent.core.tools;

import java.util.List;
import java.util.Map;

public final class Schemas {
    private Schemas() {
    }

    public static Map<String, Object> object(final Map<String, Object> properties, final String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    public static Map<String, Object> str(final String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> intProp(final String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(final String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> arrayOf(final String description, final Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }
}
