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
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The single turn execution path shared by every shell: maven-index gating
 * through {@link ShellIO#confirm}, history loading, begin/end turn
 * notifications, busy tracking and the agent loop invocation. Shells only
 * build a {@link TurnRequest} and chain on the returned stage - CLI joins it,
 * TUI chains it on the UI thread.
 */
@ApplicationScoped
public class TurnRunner {
    private final AgentLoop agentLoop;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;
    private final VirtualThreadExecutor executor;

    public TurnRunner(
            final AgentLoop agentLoop,
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final VirtualThreadExecutor executor) {
        this.agentLoop = agentLoop;
        this.mavenKnowledgeService = mavenKnowledgeService;
        this.executor = executor;
    }

    protected TurnRunner() {
        this.agentLoop = null;
        this.mavenKnowledgeService = null;
        this.executor = null;
    }

    /**
     * A turn to run: {@code prompt == null} means a resume (re-runs the agent
     * loop on the persisted history without sending any message).
     *
     * @param queuedPrompt when non-null the running loop polls this source between iterations and
     *                     absorbs each returned prompt into the current turn instead of starting a
     *                     new one; the shell owns the queue backing it.
     */
    public record TurnRequest(
            ShellEnv env,
            String sessionId,
            String agentName,
            String prompt,
            Supplier<List<Message>> history,
            BooleanSupplier cancelled,
            Supplier<String> queuedPrompt) {

        public TurnRequest(
                final ShellEnv env,
                final String sessionId,
                final String agentName,
                final String prompt,
                final Supplier<List<Message>> history,
                final BooleanSupplier cancelled) {
            this(env, sessionId, agentName, prompt, history, cancelled, null);
        }
    }

    /**
     * Runs the turn after the maven-index gate. Returns {@code false} without
     * executing anything when the user declined to send while indexing is
     * still in flight; the caller owns the wording of that notice.
     */
    public CompletionStage<Boolean> submit(final TurnRequest request) {
        final var run = mavenKnowledgeService.currentIndex();
        if (run == null || !run.inFlight()) {
            return execute(request);
        }
        final var env = request.env();
        return env.io()
                .confirm("maven knowledge index still running (" + run.doneCount() + "/" + run.total()
                        + ") - send the prompt anyway?")
                .thenCompose(sendAnyway -> Boolean.TRUE.equals(sendAnyway)
                        ? execute(request)
                        : CompletableFuture.completedStage(Boolean.FALSE));
    }

    private CompletionStage<Boolean> execute(final TurnRequest request) {
        final var env = request.env();
        final var history = request.history().get();
        env.frontend().beginTurn();
        env.session().busy(true);
        final var done = new CompletableFuture<Boolean>();
        executor.execute(() -> {
            try {
                if (request.prompt() == null) {
                    agentLoop.resume(
                            request.agentName(),
                            request.sessionId(),
                            history,
                            request.cancelled(),
                            request.queuedPrompt());
                } else {
                    agentLoop.run(
                            request.prompt(),
                            request.agentName(),
                            request.sessionId(),
                            history,
                            request.cancelled(),
                            request.queuedPrompt());
                }
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            } finally {
                env.session().busy(false);
                env.frontend().endTurn();
                // completing last so awaiters observe the turn fully finished
                done.complete(Boolean.TRUE);
            }
        });
        return done;
    }
}
