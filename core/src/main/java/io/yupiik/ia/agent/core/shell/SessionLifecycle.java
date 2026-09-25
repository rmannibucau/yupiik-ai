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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Session plumbing shared by the shells: session id resolution from the
 * command options, context file preparation and gate installation/teardown.
 * Rendering is left to the callers; this class only mutates the services.
 * Gates and permission state belong to the {@link SessionRuntime} of the
 * session being wired, so concurrent tabs never share them.
 */
@ApplicationScoped
public class SessionLifecycle {
    private final SessionStore sessionStore;

    public SessionLifecycle(final SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * Resolves the session id: explicit {@code session} option first, then the
     * {@code context} file name (without its {@code .jsonl} extension),
     * otherwise a freshly created session.
     */
    public String resolveSessionId(final String session, final String context) {
        if (session != null && !session.isBlank()) {
            return session;
        }
        if (context != null && !context.isBlank()) {
            var name = Path.of(context).getFileName().toString();
            if (name.endsWith(".jsonl")) {
                name = name.substring(0, name.length() - ".jsonl".length());
            }
            return name;
        }
        return sessionStore.create();
    }

    /**
     * Creates the parent directories and an empty context file when the user
     * passed an explicit {@code context} option; no-op otherwise.
     */
    public void prepareContextFile(final Path contextPath, final String context) throws IOException {
        if (context == null || context.isBlank()) {
            return;
        }
        Files.createDirectories(contextPath.getParent() == null ? Path.of(".") : contextPath.getParent());
        if (!Files.isRegularFile(contextPath)) {
            Files.writeString(contextPath, "");
        }
    }

    /** Installs the interaction gates on the session runtime and applies the permit-all switch. */
    public void installGates(
            final ApprovalGate approvalGate,
            final FeedbackGate feedbackGate,
            final boolean permitAll,
            final SessionRuntime runtime) {
        final var permissions = runtime.permissions();
        permissions.setPermitAll(permitAll);
        permissions.setPermissionMode(null);
        permissions.setGate(approvalGate);
        runtime.feedback().setGate(feedbackGate);
    }

    /**
     * Removes the session's gates, clears its permit-all switch and deletes the
     * session when it holds no message (so aborted runs do not leave empty files).
     * Other sessions keep their own gates and switches.
     */
    public void release(final String sessionId, final SessionRuntime runtime) {
        if (runtime != null) {
            final var permissions = runtime.permissions();
            permissions.setGate(null);
            permissions.setPermitAll(false);
            permissions.setPermissionMode(null);
            permissions.setMode(null);
            runtime.feedback().setGate(null);
        }
        if (sessionId != null && sessionStore.isEmpty(sessionId)) {
            sessionStore.delete(sessionId);
        }
    }
}
