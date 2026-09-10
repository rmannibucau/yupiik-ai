package io.yupiik.ia.agent.core.llm;

/**
 * Signals a user-visible delay imposed by the {@link ChatClient} resilience layer: rate-limit throttling
 * or an exponential-backoff retry. Emitted so CLI / TUI front ends can surface "waiting" feedback.
 */
public record PauseEvent(Pause kind, String message, long durationMs) {

    public enum Pause {THROTTLE, RETRY}

    public static PauseEvent throttle(final String message, final long durationMs) {
        return new PauseEvent(Pause.THROTTLE, message, durationMs);
    }

    public static PauseEvent retry(final String message, final long durationMs) {
        return new PauseEvent(Pause.RETRY, message, durationMs);
    }
}