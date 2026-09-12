package io.yupiik.ia.agent.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {
    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void plainTextEstimate() {
        assertEquals(3, TokenEstimator.estimate("abcdefgh"));
    }

    @Test
    void codeSyntaxCostsMore() {
        final var plain = TokenEstimator.estimate("abcdef");
        final var code = TokenEstimator.estimate("a{b}=c");
        assertEquals(4, code);
        assertTrue(code > plain * 0.8);
    }
}
