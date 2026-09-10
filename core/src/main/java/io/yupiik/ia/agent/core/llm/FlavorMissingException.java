package io.yupiik.ia.agent.core.llm;

import java.io.IOException;

/**
 * Thrown when a provider does not implement an API flavor (HTTP 404/405/501), enabling an
 * AUTO dispatcher to fall back from the responses flavor to the completions flavor.
 */
public final class FlavorMissingException extends IOException {
    public FlavorMissingException(final String flavor) {
        super(flavor + " flavor not supported by the provider");
    }
}