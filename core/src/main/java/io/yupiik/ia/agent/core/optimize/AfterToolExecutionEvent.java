package io.yupiik.ia.agent.core.optimize;

public record AfterToolExecutionEvent(String tool, boolean success, String output) {
}
