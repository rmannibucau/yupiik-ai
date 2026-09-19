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
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Session plumbing shared by the shells: session id resolution from the
 * command options, context file preparation and gate installation/teardown.
 * Rendering is left to the callers; this class only mutates the services.
 */
@ApplicationScoped
public class SessionLifecycle {
    private final SessionStore sessionStore;
    private final PermissionService permissionService;
    private final UserFeedbackService userFeedbackService;

    public SessionLifecycle(
            final SessionStore sessionStore,
            final PermissionService permissionService,
            final UserFeedbackService userFeedbackService) {
        this.sessionStore = sessionStore;
        this.permissionService = permissionService;
        this.userFeedbackService = userFeedbackService;
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

    /** Installs the interaction gates and applies the permit-all switch. */
    public void installGates(
            final ApprovalGate approvalGate, final FeedbackGate feedbackGate, final boolean permitAll) {
        permissionService.setPermitAll(permitAll);
        permissionService.setGate(approvalGate);
        userFeedbackService.setGate(feedbackGate);
    }

    /**
     * Removes the gates, clears the permit-all switch and deletes the session
     * when it holds no message (so aborted runs do not leave empty files).
     */
    public void release(final String sessionId) {
        permissionService.setGate(null);
        permissionService.setPermitAll(false);
        userFeedbackService.setGate(null);
        if (sessionId != null && sessionStore.isEmpty(sessionId)) {
            sessionStore.delete(sessionId);
        }
    }

    PermissionService permissionService() {
        return permissionService;
    }
}
