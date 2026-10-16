/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.llm.client.codex.CodexOAuthClient;
import io.yupiik.llm.client.codex.CodexOAuthConfig;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns the Codex login flow and token persistence, shared by the {@code /codex login} slash command
 * and the frontends' auto-login-on-first-use. Tokens are written to
 * {@code <storageHome>/providers/<provider>/codex.auth.json}, the exact location {@link
 * CodexAuthTemplateHelper} reads for {@code {{codexAuth}}}, so a session created here is picked up
 * automatically on the next request.
 */
@ApplicationScoped
public class CodexLoginService {

    private final JsonMapper mapper;
    private final ExtendedHttpClient httpClient;
    private final Path providersHome;

    public CodexLoginService(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.httpClient = httpClient;
        final String home = configuration == null ? null : configuration.resolveStorageHome();
        this.providersHome = Path.of(home == null || home.isBlank() ? "~/.yupiik/coding-agent" : home)
                .resolve("providers");
    }

    /**
     * Runs the interactive ChatGPT/Codex login for a provider and persists the session. The browser
     * PKCE flow is the default; {@code device} opts into the device-code flow (the CLI's
     * {@code --device-auth} path). Blocks until the user authorizes (or {@code cancelled} returns
     * true).
     *
     * @param provider the provider id to sign in.
     * @param device true to use the device-code flow instead of the browser flow.
     * @param urlSink receives the authorize (or verification) URL to open; the frontend decides how to
     *     surface it (print, TUI modal, or open in a web tab).
     * @param cancelled cancellation probe.
     * @return the authenticated session.
     */
    public CodexTokenStore signIn(
            final String provider,
            final boolean device,
            final Consumer<String> urlSink,
            final Predicate<Boolean> cancelled) {
        final var oauth = new CodexOAuthClient(mapper, httpClient, CodexOAuthConfig.DEFAULT);
        final CodexTokenStore store;
        if (device) {
            final var challenge = oauth.deviceCode();
            if (urlSink != null) {
                urlSink.accept(challenge.prompt());
            }
            store = oauth.poll(challenge, cancelled);
        } else {
            store = oauth.browserFlow(urlSink, cancelled);
        }
        save(provider, store);
        return store;
    }

    /** The token store for a provider, or {@code null} when no session exists yet. */
    public CodexTokenStore load(final String provider) {
        final Path file = codexAuthFile(provider);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.fromBytes(CodexTokenStore.class, Files.readAllBytes(file));
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read the Codex auth file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Persists a session atomically (temp file + move). */
    public void save(final String provider, final CodexTokenStore store) {
        final var file = codexAuthFile(provider);
        try {
            Files.createDirectories(file.getParent());
            final var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.toBytes(store));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot persist the Codex session to " + file + ": " + e.getMessage(), e);
        }
    }

    private Path codexAuthFile(final String provider) {
        return providersHome.resolve(provider).resolve("codex.auth.json");
    }
}
