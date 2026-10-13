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

import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Derives provider-agnostic sampling defaults (temperature, topP, topK) and the implicit reasoning
 * variant scheme from a model family, so a request that does not set them explicitly still gets
 * sensible values instead of the provider random default. Highly inspired from the {code temperature},
 * {code topP}, {code topK} and {code variants} model default helpers of opencode.
 *
 * <p>Every method returns {@code null} when no default applies: the parameter is then simply not
 * sent and the provider applies its own default. Comparisons are case-insensitive on the model
 * name so model identifiers from different vendors (gpt, claude, gemini, deepseek, minimax, kimi,
 * glm) are detected consistently.</p>
 */
public final class DefaultModelTuner {
    private DefaultModelTuner() {
        // utility
    }

    /** Gemini models that carry explicit sampling defaults (see opencode). */
    private static final List<Pattern> GEMINI_SAMPLING_DEFAULTS = List.of(
            Pattern.compile("gemini-2[.-]5(?:[.-]|$)"),
            Pattern.compile("gemini-3-(?:flash|pro)(?:[.-]|$)"),
            Pattern.compile("gemini-3[.-]1(?:[.-]|$)"),
            Pattern.compile("gemini-3[.-]5-flash(?!-lite)(?:[.-]|$)"));

    // Reasoning effort tiers and gpt-5 family helpers, mirroring opencode transform.ts.

    private static final List<String> WIDELY_SUPPORTED_EFFORTS = List.of("low", "medium", "high");
    private static final List<String> OPENAI_EFFORTS = List.of("none", "minimal", "low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_1_EFFORTS = List.of("none", "low", "medium", "high");
    private static final List<String> OPENAI_GPT5_2_PLUS_EFFORTS = List.of("none", "low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_PRO_EFFORTS = List.of("high");
    private static final List<String> OPENAI_GPT5_PRO_2_PLUS_EFFORTS = List.of("medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_CHAT_EFFORTS = List.of("medium");
    private static final List<String> OPENAI_GPT5_CODEX_XHIGH_EFFORTS = List.of("low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_GPT5_CODEX_3_PLUS_EFFORTS =
            List.of("none", "low", "medium", "high", "xhigh");

    private static final String OPENAI_NONE_EFFORT_RELEASE_DATE = "2025-11-13";
    private static final String OPENAI_XHIGH_EFFORT_RELEASE_DATE = "2025-12-04";

    private static final Pattern GPT5_FAMILY_RE = Pattern.compile("(?:^|/)gpt-5(?:[.-]|$)");
    private static final Pattern GPT5_VERSION_RE = Pattern.compile("(?:^|/)gpt-5[.-](\\d+)(?:[.-]|$)");
    private static final Pattern GPT5_PRO_RE = Pattern.compile("(?:^|/)gpt-5[.-]?pro(?:[.-]|$)");
    private static final Pattern GPT5_VERSIONED_PRO_RE = Pattern.compile("(?:^|/)gpt-5[.-]\\d+[.-]pro(?:[.-]|$)");
    private static final Pattern GPT5_CHAT_RE = Pattern.compile(("(?:^|/)gpt-5[.-]?[a-z0-9]*chat(?:[.-]|$)"));

    private static final Pattern CLAUDE_VERSION_RE =
            Pattern.compile("claude-(?:[a-z]+-)?(\\d+)(?:[.-](\\d{1,2}))?(?:[.@-]|$)");

    /** Default temperature from a model name and provider id; null defers to the provider. */
    public static Double temperature(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (m.startsWith("north-mini-code")) {
            return 1.0;
        }
        if (m.startsWith("claude")) {
            return null;
        }
        if (isGemini(m)) {
            return 1.0;
        }
        if (m.startsWith("glm-4.6") || m.startsWith("glm-4.7")) {
            return 1.0;
        }
        if (m.startsWith("minimax-m2")) {
            return 1.0;
        }
        if (m.startsWith("kimi-k2")) {
            if (isKimiK2(m)) {
                return 1.0;
            }
            return 0.6;
        }
        return null;
    }

    /** Default topP from a model name and provider id; null defers to the provider. */
    public static Double topP(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (isGemini(m)) {
            return 0.95;
        }
        if (m.startsWith("minimax-m2")
                || m.startsWith("kimi-k2.5")
                || m.startsWith("kimi-k2p5")
                || m.startsWith("kimi-k2-5")) {
            return 0.95;
        }
        if (m.contains("deepseek-v4-flash-0731") || m.contains("deepseek-v4-flash:0731")) {
            return 0.95;
        }
        final var pid = providerId == null ? null : providerId.toLowerCase(Locale.ROOT);
        if (m.startsWith("deepseek-v4-flash")
                && (pid == null || pid.equals("deepseek") || pid.startsWith("opencode"))) {
            return 0.95;
        }
        return null;
    }

    /** Default topK from a model name and provider id; null defers to the provider. */
    public static Double topK(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (m.startsWith("minimax-m2")) {
            return m.contains("m2.") || m.contains("m25") || m.contains("m21") ? 40.0 : 20.0;
        }
        if (isGemini(m)) {
            return 64.0;
        }
        return null;
    }

    /** A synthetic default variant name usable by the {@code /model} command when no variants are configured. */
    public static String implicitVariantName(final String model, final String providerId) {
        if (temperature(model, providerId) != null
                || topP(model, providerId) != null
                || topK(model, providerId) != null) {
            return "default";
        }
        return null;
    }

    /**
     * The context in which the implicit reasoning variant scheme is derived, mirroring the model facets
     * opencode reads (model id, provider id, npm package as our {@link ApiFlavor}, release date and
     * output budget).
     *
     * @param model model id (normalized during matching)
     * @param providerId provider id, used for a best-effort match against opencode catalog provider slugs
     * @param dialect the API wire flavor (our stand-in for opencode's npm package)
     * @param maxOutputTokens the resolved output budget, fine for the anthropic budget math (nullable)
     * @param releaseDate optional ISO-8601 release date (yyyy-MM-dd) unlocking OpenAI none/xhigh tiers
     */
    public record ModelContext(
            String model, String providerId, ApiFlavor dialect, Integer maxOutputTokens, String releaseDate) {}

    /**
     * Reasoning-effort variants exposed for reasoning-capable families that have no configured variants.
     * A faithful port of opencode's {@code variants()} resolver, keyed on the wire flavor (COMPLETIONS /
     * RESPONSES as OpenAI-compatible, ANTHROPIC as Anthropic-compatible) and discriminated further by the
     * model id and, as a best-effort, the provider id. The flavor-less form assumes the plain
     * OpenAI-compatible chat wire (chat completions), the common layout opencode uses.
     */
    public static List<ModelVariantConfiguration> variants(final String model, final String providerId) {
        return variants(new ModelContext(model, providerId, ApiFlavor.COMPLETIONS, null, null));
    }

    /** Rich variant resolution from a {@link ModelContext}; see {@link #variants(String, String)}. */
    public static List<ModelVariantConfiguration> variants(final ModelContext ctx) {
        final var m = norm(ctx.model());
        if (m == null) {
            return List.of();
        }
        final var dialect = ctx.dialect() == null ? ApiFlavor.COMPLETIONS : ctx.dialect();
        final boolean anthropic = dialect == ApiFlavor.ANTHROPIC;
        final boolean openaiWire = dialect == ApiFlavor.COMPLETIONS || dialect == ApiFlavor.RESPONSES;

        // deepseek names excluded by opencode before any other resolution.
        if (m.contains("deepseek-chat")
                || m.contains("deepseek-reasoner")
                || m.contains("deepseek-r1")
                || m.contains("deepseek-v3")) {
            return List.of();
        }

        final boolean glm52 = m.contains("glm-5.2") || m.contains("glm-5-2") || m.contains("glm-5p2");
        final boolean isKimi = isKimiFamily(m, ctx.providerId());
        final String adaptiveEfforts = anthropicAdaptiveEfforts(m);
        final String id = m;

        // minimax-m3 on the anthropic or OpenAI-compatible bounds uses a thinking toggle.
        if (id.contains("minimax-m3") && (anthropic || openaiWire)) {
            if (providerMatches(ctx.providerId(), "nvidia", "lilac")) {
                return List.of(
                        variant("none", null, "{\"chat_template_kwargs\":{\"thinking_mode\":\"disabled\"}}"),
                        variant("thinking", null, "{\"chat_template_kwargs\":{\"thinking_mode\":\"enabled\"}}"));
            }
            return List.of(
                    variant("none", null, "{\"thinking\":{\"type\":\"disabled\"}}"),
                    variant("thinking", null, "{\"thinking\":{\"type\":\"adaptive\"}}"));
        }

        // GLM-5.2 high/max on each wire.
        if (glm52 && openaiWire) {
            return List.of(variant("high", "high", null), variant("max", "max", null));
        }
        if (glm52 && anthropic) {
            return List.of(
                    variant("high", null, "{\"output_config\":{\"effort\":\"high\"}}"),
                    variant("max", null, "{\"output_config\":{\"effort\":\"max\"}}"));
        }

        // Kimi on the Anthropic-compatible wire uses adaptive efforts low..max.
        if (isKimi && anthropic) {
            final var vars = new ArrayList<ModelVariantConfiguration>();
            for (final var effort : List.of("low", "medium", "high", "xhigh", "max")) {
                vars.add(variant(
                        effort,
                        null,
                        "{\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"},\"output_config\":{\"effort\":\""
                                + effort + "\"}}"));
            }
            return vars;
        }

        // Early-exclude families opencode does not expose variants for.
        if (id.contains("minimax")
                || (m.contains("glm") && !glm52)
                || id.contains("kimi")
                || id.contains("k2p")
                || id.contains("qwen")
                || id.contains("big-pickle")) {
            return List.of();
        }

        // grok-3-mini exposes low/high.
        if (id.contains("grok") && id.contains("grok-3-mini")) {
            return List.of(variant("low", "low", null), variant("high", "high", null));
        }

        if (anthropic) {
            return anthropicVariants(ctx, m);
        }
        if (openaiWire) {
            return openAiCompatibleVariants(ctx, m);
        }
        return List.of();
    }

    /**
     * OpenAI-compatible wire: model-id expert efforts, else widely-supported tiers with a deepseek-v4 max.
     * The provider id gives a best-effort match against opencode's OpenAI/azure/meta/copilot catalog slugs.
     */
    private static List<ModelVariantConfiguration> openAiCompatibleVariants(final ModelContext ctx, final String m) {
        if (m.contains("north-mini-code")) {
            return List.of(variant("none", "none", null), variant("high", "high", null));
        }

        final var providerId = ctx.providerId();
        // meta exposes the full OpenAI set on the openai package.
        if (providerMatches(providerId, "meta")) {
            return efforts(OPENAI_EFFORTS, DefaultModelTuner::openAiReasoningSuffix);
        }
        // groq exposes none + widely supported.
        if (providerMatches(providerId, "groq")) {
            return efforts(List.of("none", "low", "medium", "high"), DefaultModelTuner::reasoningEffortOnly);
        }
        // mistral exposes only high on the few reasoning models.
        if (providerMatches(providerId, "mistral")) {
            if (List.of("mistral-small-2603", "mistral-small-latest", "mistral-medium-3.5", "mistral-medium-2604")
                    .stream()
                    .noneMatch(m::contains)) {
                return List.of();
            }
            return List.of(variant("high", "high", null));
        }
        // azure: o1-mini has no variants, everything else the openai tiers.
        if (providerMatches(providerId, "azure")) {
            if (m.equals("o1-mini")) {
                return List.of();
            }
            return efforts(openAiReasoningEfforts(m, ctx.releaseDate()), DefaultModelTuner::openAiReasoningSuffix);
        }
        // openai package / mantle: meta handled above, else openai tiers.
        if (providerMatches(providerId, "openai") || providerMatches(providerId, "mantle")) {
            return efforts(openAiReasoningEfforts(m, ctx.releaseDate()), DefaultModelTuner::openAiReasoningSuffix);
        }
        // copilot: no variants for gemini; claude -> widely supported; else gpt tiers (+xhigh late 2025).
        if (providerMatches(providerId, "github-copilot", "copilot")) {
            if (m.contains("gemini")) {
                return List.of();
            }
            if (m.contains("claude")) {
                return efforts(WIDELY_SUPPORTED_EFFORTS, DefaultModelTuner::reasoningEffortOnly);
            }
            var efforts = new ArrayList<String>(WIDELY_SUPPORTED_EFFORTS);
            if (m.contains("5.1-codex-max") || m.contains("5.2") || m.contains("5.3")) {
                efforts.add("xhigh");
            } else if (gpt5Family(m)
                    && ctx.releaseDate() != null
                    && ctx.releaseDate().compareTo(OPENAI_XHIGH_EFFORT_RELEASE_DATE) >= 0) {
                efforts.add("xhigh");
            }
            return List.of(
                    variant("low", "low", reasoningExtras()),
                    variant("medium", "medium", reasoningExtras()),
                    variant("high", "high", reasoningExtras()),
                    efforts.contains("xhigh") ? variant("xhigh", "xhigh", reasoningExtras()) : null);
        }
        // Generic OpenAI-compatible / cerebras / togetherai / xai / deepinfra / venice.
        var efforts = new ArrayList<String>(WIDELY_SUPPORTED_EFFORTS);
        if (m.contains("deepseek-v4")) {
            efforts.add("max");
        }
        return efforts(efforts, DefaultModelTuner::reasoningEffortOnly);
    }

    /** Anthropic-compatible wire: adaptive efforts when modern, opus-4.5 widely-supported, else token budgets. */
    private static List<ModelVariantConfiguration> anthropicVariants(final ModelContext ctx, final String m) {
        final String adaptiveEfforts = anthropicAdaptiveEfforts(m);
        if (adaptiveEfforts != null) {
            final boolean omitted = anthropicOmitsThinking(m);
            final var vars = new ArrayList<ModelVariantConfiguration>();
            for (final var effort : adaptiveEfforts.split(",")) {
                final var display = omitted ? ",\"display\":\"summarized\"" : "";
                // effort lives in output_config per the Messages API (a top-level 'effort' is not a
                // documented parameter and strict endpoints reject or ignore it)
                vars.add(variant(
                        effort,
                        null,
                        "{\"thinking\":{\"type\":\"adaptive\"" + display + "},\"output_config\":{\"effort\":\"" + effort
                                + "\"}}"));
            }
            return vars;
        }
        if (anthropicOpus45(m)) {
            final var vars = new ArrayList<ModelVariantConfiguration>();
            for (final var effort : WIDELY_SUPPORTED_EFFORTS) {
                vars.add(variant(
                        effort,
                        null,
                        "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + opus45Budget(ctx, effort) + "}}"));
            }
            return vars;
        }
        final var out = ctx.maxOutputTokens() == null || ctx.maxOutputTokens() <= 0
                ? OutputBudgetResolver.DEFAULT_MAX_TOKEN
                : ctx.maxOutputTokens();
        final int highBudget = Math.min(16_000, Math.max(1, out / 2 - 1));
        final int maxBudget = Math.min(31_999, Math.max(1, out - 1));
        return List.of(
                variant("high", null, "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + highBudget + "}}"),
                variant("max", null, "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + maxBudget + "}}"));
    }

    private static long opus45Budget(final ModelContext ctx, final String effort) {
        // opencode keeps the widely-supported default for opus-4.5; we reuse the generic budget maths.
        final var out = ctx.maxOutputTokens() == null || ctx.maxOutputTokens() <= 0
                ? OutputBudgetResolver.DEFAULT_MAX_TOKEN
                : ctx.maxOutputTokens();
        return "high".equals(effort)
                ? Math.min(16_000, Math.max(1, out / 2 - 1))
                : Math.min(31_999, Math.max(1, out - 1));
    }

    /** OpenAI reasoning effort tiers for a model id + release date, mirroring opencode's openaiReasoningEfforts. */
    private static List<String> openAiReasoningEfforts(final String apiId, final String releaseDate) {
        final var id = apiId.toLowerCase(Locale.ROOT);
        if (id.contains("deep-research")) {
            return List.of("medium");
        }
        final var chat = gpt5ChatReasoningEfforts(id);
        if (chat != null) {
            return chat;
        }
        if (GPT5_PRO_RE.matcher(id).find()) {
            return OPENAI_GPT5_PRO_EFFORTS;
        }
        final var codex = gpt5CodexReasoningEfforts(id);
        if (codex != null) {
            return codex;
        }
        final var versioned = versionedGpt5ReasoningEfforts(id);
        if (versioned != null) {
            return versioned;
        }
        final var efforts = new ArrayList<String>(WIDELY_SUPPORTED_EFFORTS);
        if (gpt5Family(id)) {
            efforts.add(0, "minimal");
        }
        if (releaseDate != null && releaseDate.compareTo(OPENAI_NONE_EFFORT_RELEASE_DATE) >= 0) {
            efforts.add(0, "none");
        }
        if (releaseDate != null && releaseDate.compareTo(OPENAI_XHIGH_EFFORT_RELEASE_DATE) >= 0) {
            efforts.add("xhigh");
        }
        return efforts;
    }

    private static List<String> versionedGpt5ReasoningEfforts(final String apiId) {
        if (GPT5_VERSIONED_PRO_RE.matcher(apiId).find()) {
            return OPENAI_GPT5_PRO_2_PLUS_EFFORTS;
        }
        final var version = gpt5Version(apiId);
        if (version == null) {
            return null;
        }
        return version == 1 ? OPENAI_GPT5_1_EFFORTS : OPENAI_GPT5_2_PLUS_EFFORTS;
    }

    private static List<String> gpt5CodexReasoningEfforts(final String apiId) {
        if (!gpt5Family(apiId) || !apiId.contains("codex")) {
            return null;
        }
        final var version = gpt5Version(apiId);
        if (version != null && version >= 3) {
            return OPENAI_GPT5_CODEX_3_PLUS_EFFORTS;
        }
        if (apiId.contains("codex-max") || (version != null && version >= 2)) {
            return OPENAI_GPT5_CODEX_XHIGH_EFFORTS;
        }
        return WIDELY_SUPPORTED_EFFORTS;
    }

    private static List<String> gpt5ChatReasoningEfforts(final String apiId) {
        if (!gpt5Family(apiId) || !apiId.contains("-chat")) {
            return null;
        }
        return gpt5Version(apiId) == null ? List.of() : OPENAI_GPT5_CHAT_EFFORTS;
    }

    private static boolean gpt5Family(final String apiId) {
        return GPT5_FAMILY_RE.matcher(apiId).find();
    }

    private static Integer gpt5Version(final String apiId) {
        final var matcher = GPT5_VERSION_RE.matcher(apiId);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** Anthropic adaptive effort tiers for a model id, or null when the model predates adaptive thinking. */
    private static String anthropicAdaptiveEfforts(final String apiId) {
        if (anthropicUsesModernAdaptiveThinking(apiId)) {
            return "low,medium,high,xhigh,max";
        }
        if (List.of(
                        "opus-4-6",
                        "opus-4.6",
                        "4-6-opus",
                        "4.6-opus",
                        "sonnet-4-6",
                        "sonnet-4.6",
                        "4-6-sonnet",
                        "4.6-sonnet")
                .stream()
                .anyMatch(apiId::contains)) {
            return "low,medium,high,max";
        }
        return null;
    }

    private static boolean anthropicUsesModernAdaptiveThinking(final String apiId) {
        if (!apiId.toLowerCase(Locale.ROOT).contains("claude-")) {
            return false;
        }
        final var matcher = CLAUDE_VERSION_RE.matcher(apiId);
        if (!matcher.find()) {
            return true;
        }
        final var major = Integer.parseInt(matcher.group(1));
        final var minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return major > 4 || (major == 4 && minor >= 7);
    }

    private static boolean anthropicOmitsThinking(final String apiId) {
        return anthropicUsesModernAdaptiveThinking(apiId);
    }

    private static boolean anthropicOpus45(final String apiId) {
        return apiId.contains("opus-4-5") || apiId.contains("opus-4.5");
    }

    /** Kimi/Moonshot family detection from the model id (and provider id as a fallback). */
    private static boolean isKimiFamily(final String model, final String providerId) {
        if (model.contains("kimi") || model.contains("moonshot")) {
            return true;
        }
        if (providerId == null) {
            return false;
        }
        final var pid = providerId.toLowerCase(Locale.ROOT);
        return pid.contains("kimi") || pid.contains("moonshot");
    }

    private static boolean providerMatches(final String providerId, final String... slugs) {
        if (providerId == null) {
            return false;
        }
        final var pid = providerId.toLowerCase(Locale.ROOT);
        for (final var slug : slugs) {
            if (pid.equals(slug) || pid.startsWith(slug)) {
                return true;
            }
        }
        return false;
    }

    private static List<ModelVariantConfiguration> efforts(
            final List<String> efforts, final Function<String, String> payload) {
        final var vars = new ArrayList<ModelVariantConfiguration>(efforts.size());
        for (final var effort : efforts) {
            vars.add(variant(effort, effort, payload.apply(effort)));
        }
        return vars;
    }

    private static String reasoningEffortOnly(final String effort) {
        return null;
    }

    private static String openAiReasoningSuffix(final String effort) {
        return "{\"reasoning_effort\":\"" + effort
                + "\",\"reasoning_summary\":\"auto\",\"include\":[\"reasoning.encrypted_content\"]}";
    }

    private static String reasoningExtras() {
        return "{\"reasoning_summary\":\"auto\",\"include\":[\"reasoning.encrypted_content\"]}";
    }

    private static ModelVariantConfiguration variant(final String name, final String effort, final String extra) {
        return new ModelVariantConfiguration(name, null, null, null, null, effort, extra);
    }

    /**
     * The implicit reasoning-effort default for a model family, mirroring opencode. The deepseek-v4
     * family defaults to {@code max} (opencode exposes {@code max} as the strongest available effort for
     * deepseek-v4); the gpt-5 family (excluding the gpt-5-chat and gpt-5-pro sub-models) defaults to
     * {@code medium}; every other family returns {@code null} so nothing is forced and the provider
     * default applies.
     */
    public static String reasoningEffort(final String model, final String providerId) {
        final var m = norm(model);
        if (m == null) {
            return null;
        }
        if (m.startsWith("deepseek-v4")) {
            return "max";
        }
        if (m.contains("gpt-5") && !m.contains("gpt-5-chat") && !m.contains("gpt-5-pro")) {
            return "medium";
        }
        return null;
    }

    /** True when the model belongs to a Gemini family carrying explicit sampling defaults. */
    private static boolean isGemini(final String model) {
        for (final var pattern : GEMINI_SAMPLING_DEFAULTS) {
            if (pattern.matcher(model).find()) {
                return true;
            }
        }
        return false;
    }

    /** True for kimi k2 reasoning families (thinking or k2. sub-versions). */
    private static boolean isKimiK2(final String model) {
        return model.contains("thinking")
                || model.matches(".*k2\\..*")
                || model.matches(".*k2p.*")
                || model.matches(".*k2-5.*");
    }

    /** Normalize a model name for matching: null/blank to null, else lower-case with trimmed. */
    private static String norm(final String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return model.trim().toLowerCase(Locale.ROOT);
    }
}
