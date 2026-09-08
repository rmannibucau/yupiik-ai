package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/**
 * Convenience wrapper around the {@link ArgsConfigSource} that lets CLI arguments drop the
 * {@code yca.} prefix: {@code --yca-storageHome}, {@code --yca-provider-model}, ... can be written
 * {@code --storageHome}, {@code --provider-model}, ...
 *
 * <p>For any requested {@code yca.<key>} it first looks up the stripped {@code <key>} in the wrapped
 * args source (from {@code --<key>}), falling back to the prefixed {@code yca.<key>} (from
 * {@code --yca-<key>}). Keys outside the {@code yca.} namespace are delegated unchanged.</p>
 */
@DefaultScoped
public class YcaPrefixShortcutConfigurationSource implements ConfigurationSource {
    private static final String PREFIX = "yca.";

    private final ArgsConfigSource delegate;

    public YcaPrefixShortcutConfigurationSource(final ArgsConfigSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public String get(final String key) {
        if (key != null && key.startsWith(PREFIX)) {
            final var stripped = key.substring(PREFIX.length());
            final var strippedValue = delegate.get(stripped);
            if (strippedValue != null) {
                return strippedValue;
            }
            return delegate.get(key);
        }
        return delegate.get(key);
    }
}