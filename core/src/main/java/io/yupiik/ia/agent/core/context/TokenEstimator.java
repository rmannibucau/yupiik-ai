package io.yupiik.ia.agent.core.context;

public final class TokenEstimator {
    private TokenEstimator() {
    }

    public static int estimate(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var tokens = 0.0;
        var codeChars = 0;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '{' || c == '}' || c == '(' || c == ')' || c == '=' || c == ';' || c == '<' || c == '>' || c == '_' || c == '"') {
                codeChars++;
            }
        }
        tokens += text.length() * 0.28;
        tokens += codeChars * 0.25;
        return (int) Math.ceil(tokens);
    }
}
