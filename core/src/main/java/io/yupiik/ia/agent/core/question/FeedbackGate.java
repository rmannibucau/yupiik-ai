package io.yupiik.ia.agent.core.question;

import java.util.List;
import java.util.Map;

public interface FeedbackGate {
    Map<String, Object> ask(List<Question> questions);
}