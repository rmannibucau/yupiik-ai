package io.yupiik.ia.agent.core.question;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class UserFeedbackService {
    private volatile FeedbackGate gate;

    public void setGate(final FeedbackGate gate) {
        this.gate = gate;
    }

    public Map<String, Object> ask(final List<Question> questions) {
        final var g = gate;
        if (g == null) {
            return null;
        }
        return g.ask(questions);
    }
}