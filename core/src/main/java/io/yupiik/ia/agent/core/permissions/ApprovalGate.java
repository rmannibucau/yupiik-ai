package io.yupiik.ia.agent.core.permissions;

public interface ApprovalGate {
    boolean approve(String title, String detail);
}
