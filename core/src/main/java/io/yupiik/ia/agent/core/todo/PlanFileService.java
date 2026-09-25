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
package io.yupiik.ia.agent.core.todo;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared export/parse/reload of the editable plan file. Both the TamboUI
 * {@code tui} and the plain-terminal {@code cli} expose the plan as a markdown
 * file with a {@code # Plan} section (the plan text) and a {@code # Todos}
 * section (one {@code - [ ]}/{@code - [>]}/{@code - [x]} item per line) so the
 * user can adjust both before entering build mode.
 */
@ApplicationScoped
public class PlanFileService {
    private final SessionStore sessionStore;

    public PlanFileService(final SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    protected PlanFileService() {
        this.sessionStore = null;
    }

    /**
     * Default location for a session plan file under the given storage home.
     *
     * @param storageHome resolved storage root.
     * @param sessionId   the session id.
     * @param title       a human session title used to build a readable file name.
     */
    public Path defaultPath(final Path storageHome, final String title, final String sessionId) {
        final var base = storageHome.resolve("plans");
        final var name = sanitize(title == null || title.isBlank() ? "plan" : title) + "-" + sessionId + ".plan.md";
        return base.resolve(name);
    }

    /**
     * @return the markdown plan file content for the given plan and todos.
     */
    public String render(final String plan, final List<TodoItem> todos) {
        final var sb = new StringBuilder("# Plan\n\n").append(plan).append("\n\n# Todos\n");
        if (todos == null || todos.isEmpty()) {
            sb.append("\n- [ ] (no todos yet)\n");
        } else {
            for (final var t : todos) {
                final var mark =
                        switch (t.status() == null ? "" : t.status()) {
                            case "in_progress" -> "[>]";
                            case "completed" -> "[x]";
                            default -> "[ ]";
                        };
                sb.append("\n- ").append(mark).append(' ').append(t.content()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * @return the plan + todos parsed back from a plan file.
     */
    public PlanFile parse(final String content) {
        final var plan = section(content, "Plan");
        final var todoSection = section(content, "Todos");
        final var todos = new ArrayList<TodoItem>();
        if (todoSection != null) {
            for (final var line : todoSection.lines().toList()) {
                final var trimmed = line.strip();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }
                final var item = trimmed.replaceFirst("^[-*+]\\s*", "");
                final String status;
                final String body;
                if (item.startsWith("[x]")) {
                    status = "completed";
                    body = item.substring(3).strip();
                } else if (item.startsWith("[>]")) {
                    status = "in_progress";
                    body = item.substring(3).strip();
                } else if (item.startsWith("[ ]")) {
                    status = "pending";
                    body = item.substring(3).strip();
                } else {
                    continue;
                }
                if (!body.isBlank()) {
                    todos.add(new TodoItem(body, status));
                }
            }
        }
        return new PlanFile(plan == null ? "" : plan, todos);
    }

    /**
     * Replaces the last assistant message of the session with {@code plan} (or
     * appends a new assistant message when there is none) and, when a non-empty
     * todo list was edited, updates the persisted todo list.
     *
     * @param sessionId target session.
     * @param parsed    parsed plan file.
     * @return a short human status string describing what was applied.
     */
    public String reload(final String sessionId, final PlanFile parsed, final TodoStore todoStore) {
        final var messages = sessionStore.load(sessionId);
        final var updated = new ArrayList<Message>();
        boolean replaced = false;
        for (final var m : messages) {
            if (!replaced && "assistant".equals(m.role()) && m.content() instanceof String) {
                updated.add(new Message(m.role(), parsed.plan(), m.name(), m.toolCalls(), m.toolCallId(), m.others()));
                replaced = true;
            } else {
                updated.add(m);
            }
        }
        if (!replaced) {
            updated.add(new Message("assistant", parsed.plan(), null, null, null, null));
        }
        sessionStore.replace(sessionId, updated);
        if (!parsed.todos().isEmpty()) {
            if (todoStore != null) {
                todoStore.replace(parsed.todos());
            }
            return "plan and todos reloaded";
        }
        return "plan reloaded";
    }

    private static String section(final String content, final String title) {
        final var marker = "# " + title;
        final var start = content.indexOf(marker);
        if (start < 0) {
            return null;
        }
        final var from = start + marker.length();
        final var next = content.indexOf("\n# ", from);
        final var end = next < 0 ? content.length() : next;
        var text = content.substring(from, end).strip();
        text = text.replaceAll("(?m)^#{2,}\\s*", "");
        if (text.equals("(no todos yet)") || text.equals("(empty)")) {
            return "";
        }
        return text;
    }

    private static String sanitize(final String title) {
        final var sb = new StringBuilder();
        for (final var c : title.strip().toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ? c : '-');
        }
        var out = sb.toString().replaceAll("-{2,}", "-").replaceAll("(^-+)|(-+$)", "");
        return out.isBlank() ? "plan" : out;
    }

    public record PlanFile(String plan, List<TodoItem> todos) {}
}
