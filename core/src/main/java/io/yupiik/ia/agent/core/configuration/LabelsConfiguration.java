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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Speaker labels rendered in the terminal transcript headers.
 *
 * <p>This is strictly a UI feature: the labels only decorate the rendered
 * markdown of {@code ConversationTranscript} (the {@code **label**} markers
 * before user/assistant blocks and the TUI turn headers). The LLM conversation
 * is always built from {@code Message} records with the hardcoded roles
 * {@code user}/{@code assistant} and never sees these labels.</p>
 */
@JsonModel
public record LabelsConfiguration(
        @Property(
                defaultValue = "\"[{{formatDate \\\"HH:mm:ss\\\"}}] {{systemProperty \\\"user.name\\\" \\\"You\\\"}}\"",
                documentation =
                        "Handlebars template rendering the user speaker label shown in transcript headers (CLI, TUI, run). "
                                + "UI only: the LLM conversation keeps its hardcoded user/assistant roles and is never affected. "
                                + "Context keys: userName (System.getProperty(\"user.name\"), missing values are skipped), modelName, "
                                + "model (name, provider), provider, agent, mode (plan/build), sessionId and configuration (the whole "
                                + "yca configuration as a map). The systemProperty helper reads a system property with an optional "
                                + "default: {{systemProperty \"user.name\" \"You\"}}. The formatDate helper renders the current "
                                + "date/time from the injected Clock: {{formatDate}} uses HH:mm:ss and "
                                + "{{formatDate \"yyyy-MM-dd HH:mm:ss\"}} accepts any java.time pattern. Remove the formatDate "
                                + "segment to disable the timestamp.")
        String userTemplate,

        @Property(
                defaultValue =
                        "\"[{{formatDate \\\"HH:mm:ss\\\"}}] {{#if model.name}}{{model.provider}}::{{model.name}}{{else}}Assistant{{/if}}\"",
                documentation =
                        "Handlebars template rendering the assistant speaker label shown in transcript headers (CLI, TUI, run). "
                                + "UI only: the LLM conversation keeps its hardcoded user/assistant roles and is never affected, "
                                + "changing the active model only changes what headers display. "
                                + "Context keys: userName, modelName, model (name, provider), provider, agent, mode (plan/build), "
                                + "sessionId and configuration (the whole yca configuration as a map). "
                                + "The default renders the active provider and model, e.g. openai::gpt-5-mini; when no model is "
                                + "resolved (no provider configured yet) it falls back to: Assistant. The formatDate helper is "
                                + "available exactly like in the user label: {{formatDate}} (HH:mm:ss) or "
                                + "{{formatDate \"pattern\"}} with any java.time pattern; remove it to disable the timestamp.")
        String assistantTemplate,

        @Property(
                defaultValue = "\"[{{formatDate \\\"HH:mm:ss\\\"}}]\"",
                documentation =
                        "Handlebars template rendering the label of the captured log blocks shown in the TUI transcript "
                                + "content view. UI only: the LLM conversation is never affected. "
                                + "Context keys: userName, modelName, model (name, provider), provider, agent, mode (plan/build), "
                                + "sessionId and configuration (the whole yca configuration as a map). "
                                + "The formatDate helper is available exactly like in the user/assistant labels: "
                                + "{{formatDate}} (HH:mm:ss) or {{formatDate \"pattern\"}} with any java.time pattern; "
                                + "remove it to disable the timestamp.")
        String logsTemplate) {

    /** The rendered user label when a template renders blank: the OS user name, else "You". */
    public static String defaultUserLabel() {
        final var name = System.getProperty("user.name");
        return name == null || name.isBlank() ? "You" : name;
    }

    /** The rendered assistant label when a template renders blank. */
    public static String defaultAssistantLabel() {
        return "Assistant";
    }

    /** The rendered log block label when a template renders blank. */
    public static String defaultLogsLabel() {
        return "Logs";
    }
}
