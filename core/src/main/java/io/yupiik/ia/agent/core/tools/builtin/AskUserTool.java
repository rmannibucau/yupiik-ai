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
package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AskUserTool implements EmbeddedTool {
    private final JsonMapper mapper;

    public AskUserTool(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    protected AskUserTool() {
        this.mapper = null;
    }

    @Override
    public String name() {
        return "ask_user";
    }

    @Override
    public String description() {
        return "Ask the user questions and get their answers. Use it whenever you need a decision, a preference or missing input the user did not specify. Each question prompts live in the terminal UI (one tab per question, then a confirm tab). Supports both single (radio) and multiple (checkbox) choice plus a free-text 'your own answer' option.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public boolean readonly() {
        return true;
    }

    @Override
    public Map<String, Object> schema() {
        final var question = Schemas.object(
                Map.of(
                        "id",
                                Schemas.str(
                                        "A stable unique identifier for this question; the answer is returned under this key."),
                        "prompt", Schemas.str("The question to ask the user."),
                        "options",
                                Schemas.arrayOf(
                                        "Proposed answers the user can pick from (radio for single, checkbox for multiple). May be empty.",
                                        Schemas.str("One proposed answer.")),
                        "multiple",
                                Schemas.bool(
                                        "false = the user picks exactly one option, true = the user can select several options.")),
                "id",
                "prompt");
        return Schemas.object(
                Map.of(
                        "questions",
                        Schemas.arrayOf(
                                "The questions to ask the user. Each gets its own tab in the prompt panel.", question)),
                "questions");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return ToolResult.error("ask_user requires an active session context.");
    }

    /**
     * The questions are routed through the asking session's feedback service
     * (passed explicitly in the context) so the prompt renders in the tab that
     * started the turn, never in another concurrently running session.
     */
    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var service = context != null ? context.feedback() : null;
        if (service == null) {
            return ToolResult.error("ask_user requires an active session feedback gate.");
        }
        final var raw = args.get("questions");
        if (!(raw instanceof final List<?> list) || list.isEmpty()) {
            return ToolResult.error("questions must be a non empty array of {id, prompt, options, multiple}");
        }
        final var questions = new ArrayList<Question>(list.size());
        for (final var item : list) {
            if (!(item instanceof final Map<?, ?> map)) {
                return ToolResult.error("each question must be an object with an id and a prompt");
            }
            final var id = String.valueOf(map.get("id"));
            String prompt = String.valueOf(map.get("prompt"));
            final var options = new ArrayList<String>();
            if (map.get("options") instanceof final List<?> opts) {
                for (final var opt : opts) {
                    if (opt instanceof final Map<?, ?> optMap) {
                        // Option object with prompt/description fields
                        final var optPrompt = String.valueOf(optMap.get("prompt"));
                        final var optDesc = String.valueOf(optMap.get("description"));
                        final var value = !"null".equals(optPrompt) && !optPrompt.isBlank()
                                ? optPrompt
                                : (!"null".equals(optDesc) && !optDesc.isBlank()
                                        ? optDesc
                                        : String.valueOf(optMap.get("id")));
                        options.add(value);
                    } else {
                        options.add(String.valueOf(opt));
                    }
                }
            }
            // Fallback: if question prompt is missing/null, use first option's prompt
            if ("null".equals(prompt) || prompt.isBlank()) {
                prompt = options.isEmpty() ? "" : options.get(0);
            }
            final var multiple = Boolean.TRUE.equals(map.get("multiple"));
            questions.add(new Question(id, prompt, options, multiple));
        }
        final var answers = service.ask(questions);
        if (answers == null) {
            return ToolResult.error("ask_user is not available in headless mode (no interactive gate).");
        }
        return ToolResult.ok(mapper.toString(answers));
    }
}
