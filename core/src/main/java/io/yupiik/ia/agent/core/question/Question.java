package io.yupiik.ia.agent.core.question;

import java.util.List;

public record Question(String id, String prompt, List<String> options, boolean multiple) {
}