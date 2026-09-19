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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;

/**
 * Terminal UI view state on top of the shared {@link ConversationTranscript}:
 * only the scroll offset used by the renderer lives here, all conversation
 * state (transcript, streaming buffers, status, usage counters) is inherited.
 */
@ApplicationScoped
public class TuiTranscriptModel extends ConversationTranscript {
    private volatile int scrollBack;

    @Override
    public synchronized void onEvent(final AgentEvent event) {
        super.onEvent(event);
        if (event instanceof AgentEvent.TextDelta
                || event instanceof AgentEvent.ToolStarted
                || event instanceof AgentEvent.TurnDone
                || event instanceof AgentEvent.Error) {
            scrollBack = 0;
        }
    }

    @Override
    public synchronized void clearConversation() {
        super.clearConversation();
        scrollBack = 0;
    }

    @Override
    public synchronized void startUserTurn(final String text) {
        super.startUserTurn(text);
        scrollBack = 0;
    }

    @Override
    public synchronized void reset() {
        super.reset();
        scrollBack = 0;
    }

    public int scrollBack() {
        return scrollBack;
    }

    public void scrollBack(final int value) {
        this.scrollBack = value;
    }
}
