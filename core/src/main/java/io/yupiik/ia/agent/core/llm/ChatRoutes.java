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

import java.util.logging.Logger;

/**
 * Derives the chat endpoint passed to a dialect from a configured base URL, which is the full LLM
 * endpoint including the API route, and detects the {@link ApiFlavor} from its suffix.
 *
 * <p>The base URL is the full chat endpoint: {@code .../v1/chat/completions} (OpenAI chat),
 * {@code .../v1/responses} (OpenAI responses) or {@code .../v1/messages} (Anthropic messages API).
 * When the base URL does not end in a known route (eg. {@code .../v1}), the default route for the
 * flavor is appended to build the chat endpoint and a warning is logged because the flavor cannot
 * be guessed reliably. The models endpoint is derived by the dialect itself from its chat route.</p>
 */
public final class ChatRoutes {
    private static final Logger LOGGER = Logger.getLogger(ChatRoutes.class.getName());

    private ChatRoutes() {
        // utility
    }

    /** Detect the API flavor from the base URL suffix; null when it cannot be guessed. */
    public static ApiFlavor detectFlavor(final String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        final var url = stripSlash(baseUrl);
        if (url.endsWith("/messages")) {
            return ApiFlavor.ANTHROPIC;
        }
        if (url.endsWith("/responses")) {
            return ApiFlavor.RESPONSES;
        }
        if (url.endsWith("/chat/completions")) {
            return ApiFlavor.COMPLETIONS;
        }
        return null;
    }

    /** The chat endpoint to hand to a dialect: the base URL when it carries a route, the flavored route otherwise. */
    public static String chatEndpoint(final String baseUrl, final ApiFlavor flavor) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        final var url = stripSlash(baseUrl);
        if (url.endsWith("/messages") || url.endsWith("/responses") || url.endsWith("/chat/completions")) {
            return url;
        }
        final var route =
                switch (flavor == null ? ApiFlavor.COMPLETIONS : flavor) {
                    case ANTHROPIC -> "/messages";
                    case RESPONSES -> "/responses";
                    default -> "/chat/completions";
                };
        final var dialect = flavor == null ? ApiFlavor.COMPLETIONS : flavor;
        LOGGER.warning(
                "Cannot reliably derive the chat endpoint from baseUrl '" + baseUrl
                        + "', assuming it is '" + url + route + "' for " + dialect.dialectName() + " dialect"
                        + ". Set the base URL to include the route (.../chat/completions, .../responses or .../messages) to remove ambiguity.");
        return url + route;
    }

    private static String stripSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
