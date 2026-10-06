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
package io.yupiik.ia.agent.core.cli;

import static io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class CliPromptTemplateTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T10:11:12.345Z"), ZoneOffset.UTC);

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Synthetic palette so tests do not depend on the real ANSI codes. Values double as the expected wrapper. */
    private static final Map<String, Object> COLORS = Map.of(
            "accent", "[accent]",
            "success", "[success]",
            "error", "[error]",
            "warning", "[warning]",
            "status", "[status]",
            "info", "[info]",
            "dim", "[dim]",
            "bold", "[bold]",
            "model", "[model]",
            "reset", "[reset]");

    private CliPromptTemplate template(final String source) {
        return withMapper(config(source));
    }

    /** Default configuration: the default template and default palette come from the @Property defaults. */
    private CliPromptTemplate defaultTemplate() {
        return withMapper(config(null));
    }

    /**
     * Bootstrap the JSON wiring exactly like production: JsonModule's JsonMapperBean collects every JsonCodec
     * bean registered by YcaCoreModule, including the generated CodingAgentConfiguration codec. Without it the
     * configuration serialization in CliPromptTemplate fails with "no codec for CodingAgentConfiguration".
     */
    private CliPromptTemplate withMapper(final CodingAgentConfiguration configuration) {
        return new CliPromptTemplate(helpers(), configuration, mapper);
    }

    private static List<PromptHandlebarsHelper> helpers() {
        return List.of(new TimePromptHelper(CLOCK), new FormatDatePromptHelper(CLOCK));
    }

    private static CodingAgentConfiguration config(final String template) {
        return codingAgent(template == null ? Map.of() : Map.of("yca.prompt.template", template));
    }

    private static Map<String, Object> data(final String mode, final String permission, final String agent) {
        return data(mode, permission, agent, 42);
    }

    private static Map<String, Object> data(
            final String mode, final String permission, final String agent, final int contextPercentage) {
        return data(mode, permission, agent, contextPercentage, COLORS);
    }

    private static Map<String, Object> data(
            final String mode,
            final String permission,
            final String agent,
            final int contextPercentage,
            final Map<String, Object> colors) {
        final var model = new LinkedHashMap<String, Object>();
        model.put("name", "gpt-x");
        model.put("contextPercentage", contextPercentage);
        model.put("contextTokens", 1000);
        model.put("contextWindow", 8000);
        final var data = new LinkedHashMap<String, Object>();
        data.put("mode", mode);
        data.put("modelName", "gpt-x");
        data.put("permission", permission);
        data.put("agent", agent);
        data.put("model", model);
        data.put("prune", 65);
        data.put("compact", 80);
        data.put("color", !colors.isEmpty());
        data.put("colors", colors);
        return data;
    }

    @Test
    void defaultTemplateRendersPlainWithoutAgentOrPermission() {
        // agent and permission blank: both {{#if}} wrappers drop their segment; empty palette degrades color to plain
        // and the reset lookups render empty, leaving the trailing space as the only difference
        final var rendered = defaultTemplate().render(data("plan", null, "", 42, Map.of()));
        assertEquals("[plan@gpt-x] 42%> ", rendered);
    }

    @Test
    void defaultTemplateRendersPermissionAndAgentWhenPresent() {
        final var rendered = defaultTemplate().render(data("build", "yolo", "reviewer", 42, Map.of()));
        assertEquals("[build@gpt-x] (yolo) [reviewer] 42%> ", rendered);
    }

    @Test
    void defaultTemplateColorizesWithPalette() {
        final var rendered = defaultTemplate().render(data("plan", "yolo", "reviewer"));
        // mode plan -> accent, reset before the '@' so the separator is uncolored
        assertTrue(rendered.contains("[accent]plan[reset]@"), "expected accent-wrapped mode: " + rendered);
        // permission yolo -> status (default branch), reset before the ')'
        assertTrue(rendered.contains("[status]yolo[reset])"), "expected status-wrapped permission: " + rendered);
        // model -> model palette entry, reset before the ']'
        assertTrue(rendered.contains("[model]gpt-x[reset]]"), "expected model-wrapped name: " + rendered);
        // 42% context -> warning band, reset after the '%' so the chevron and typed input are uncolored
        assertTrue(rendered.contains("[warning]42%[reset]> "), "expected warning-wrapped context: " + rendered);
    }

    @Test
    void defaultTemplateContextThresholds() {
        // 0-30 -> dim, 31-70 -> warning, 71+ -> error
        assertTrue(
                defaultTemplate().render(data("plan", null, "", 25)).contains("[dim]25%[reset]> "),
                "low context should be dim");
        assertTrue(
                defaultTemplate().render(data("plan", null, "", 55)).contains("[warning]55%[reset]> "),
                "mid context should be warning");
        assertTrue(
                defaultTemplate().render(data("plan", null, "", 80)).contains("[error]80%[reset]> "),
                "high context should be error");
    }

    @Test
    void lookupMissRendersEmpty() {
        // a missing palette entry renders empty (not "null"), so only the surrounding text survives
        assertEquals(
                "[pre]", template("[{{lookup colors \"missing\"}}pre]").render(data("plan", null, "", 42, COLORS)));
    }

    @Test
    void timeHelpersUseFixedClock() {
        assertEquals("10:11:12", template("{{time}}").render(data("plan", null, "")));
        assertEquals(
                "2026-09-24 10:11:12.345",
                template("{{time pattern=\"yyyy-MM-dd HH:mm:ss.SSS\"}}").render(data("plan", null, "")));
    }

    @Test
    void formatDateHelperUsesFixedClock() {
        assertEquals("10:11:12", template("{{formatDate}}").render(data("plan", null, "")));
        assertEquals(
                "2026-09-24 10:11:12.345",
                template("{{formatDate \"yyyy-MM-dd HH:mm:ss.SSS\"}}").render(data("plan", null, "")));
    }

    @Test
    void contextPercentageZeroBeforeFirstTurn() {
        assertEquals("0", template("{{{model.contextPercentage}}}").render(data("plan", null, "", 0)));
    }

    @Test
    void modeAndModelNamePlaceholders() {
        assertEquals("plan", template("{{{mode}}}").render(data("plan", null, "")));
        assertEquals("gpt-x", template("{{{modelName}}}").render(data("plan", null, "")));
        assertEquals("gpt-x", template("{{{model.name}}}").render(data("plan", null, "")));
    }

    @Test
    void configurationKeyExposesSerializedConfig() {
        final var cli = defaultTemplate();
        // the whole config is merged under the configuration key
        assertEquals("yes", template("{{#if configuration}}yes{{/if}}").render(data("plan", null, "")));
        // canonical placeholder paths resolve through the serialized map
        assertEquals("plan", template("{{{mode}}}").render(data("plan", null, "")));
        // the default color palette is reachable through the configuration key
        final var paletteEntry =
                template("{{{configuration.prompt.colors.accent}}}").render(data("plan", null, ""));
        assertTrue(paletteEntry.startsWith("\u001b[38;5;141m"), "expected ANSI accent from config: " + paletteEntry);
        // the default palette ships a reset entry closing colored segments
        assertEquals(
                "\u001b[0m",
                template("{{{configuration.prompt.colors.reset}}}").render(data("plan", null, "")),
                "expected ANSI reset from config");
        // the configured template is itself reachable through the configuration key (echoes its own source)
        final var echo = "t:{{{configuration.prompt.template}}}";
        assertEquals("t:" + echo, template(echo).render(data("plan", null, "")));
        assertTrue(cli.helpers().stream().map(PromptHandlebarsHelper::name).anyMatch("time"::equals));
    }
}
