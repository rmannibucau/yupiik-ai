package io.yupiik.ia.agent.core.permissions;

import java.util.Map;

public interface ApprovalGate {
    Result approve(String title, String detail, String toolName, Map<String, Object> args);

    enum Result {
        DENY,
        ALLOW_ONCE,
        ALLOW_SESSION
    }
}