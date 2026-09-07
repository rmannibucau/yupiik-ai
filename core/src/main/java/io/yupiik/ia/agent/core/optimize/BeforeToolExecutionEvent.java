package io.yupiik.ia.agent.core.optimize;

import java.util.Map;

public record BeforeToolExecutionEvent(String tool, Map<String, Object> args) {
}
