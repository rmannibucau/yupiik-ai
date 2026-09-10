package io.yupiik.ia.agent.core.llm;

import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A provider-specific LLM wire protocol client. Implementations translate the neutral
 * {@link ChatCompletionRequest} into their wire format and back into {@link ChatResult} /
 * {@link Message} / {@link ModelsResponse}, and apply provider-specific authentication.
 *
 * <p>These are single-shot operations targeting the passed {@link ProviderConfiguration} (so one
 * client instance can serve any provider). Network and HTTP errors (including
 * {@link ContextWindowExceededException} and, for the OpenAI responses flavor,
 * {@link FlavorMissingException}) are reported as {@link IOException}. Retries, concurrency
 * limiting and throttling are owned by the {@link ChatClient} dispatcher which wraps these calls.</p>
 */
public interface LlmClient extends AutoCloseable {

    /** The API flavor this client implements. */
    ApiFlavor flavor();

    /** Run a single (non-streaming) completion for the given provider. */
    ChatResult chat(ProviderConfiguration provider, ChatCompletionRequest request) throws IOException, InterruptedException;

    /** Run a single (non-streaming) completion for the given provider, stopping early when cancelled. */
    ChatResult chat(ProviderConfiguration provider, ChatCompletionRequest request, BooleanSupplier cancelled)
            throws IOException, InterruptedException;

    /** Run a streaming completion for the given provider, delivering deltas and returning the accumulated message. */
    Message stream(ProviderConfiguration provider, ChatCompletionRequest request, Consumer<StreamDelta> onDelta,
                   BooleanSupplier cancelled) throws IOException, InterruptedException;

    /** List the provider models; providers without a models endpoint return an empty list. */
    ModelsResponse models(ProviderConfiguration provider) throws IOException, InterruptedException;

    @Override
    void close();
}