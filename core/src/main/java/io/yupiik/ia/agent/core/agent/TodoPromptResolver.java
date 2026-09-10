package io.yupiik.ia.agent.core.agent;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;
import java.util.Locale;

/**
 * Resolves the provider-specific todo/plan guidance appendix that is injected into the system
 * prompt, mirroring how opencode tunes todo instructions per model family (for example its
 * strong "Task Management" section for Anthropic vs none for others).
 *
 * <p>The resolution is driven by the {@code yca.provider.*.prompt.todo} configuration value:
 * <ul>
 * <li>{@code auto} (the default) picks a canned block from the provider family detected in the
 * base URL, defaulting to the OpenAI block when the family is unknown;</li>
 * <li>any other non-empty value is injected verbatim as the appendix;</li>
 * <li>an empty string or the literal {@code false} disables the extra guidance.</li>
 * </ul>
 */
@ApplicationScoped
public class TodoPromptResolver {

    private record Family(String name, List<String> hints, String block) {
        private boolean matches(final String baseUrl) {
            final var url = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
            for (final var hint : hints) {
                if (url.contains(hint)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final String OPENAI = """
            Task Management:
            Use the todo_write tool to manage and plan tasks. Use it frequently so your todos track your
            progress and give the user visibility into it. It is also very useful to break a larger task down
            into smaller concrete steps.
            It is critical that you mark a todo as completed as soon as the task it describes is actually done,
            including any verification. Do not batch up several todos before marking them completed, and keep
            exactly one todo in_progress while work remains. If a step turns out to be unnecessary, mark it
            cancelled instead of leaving it pending.""";

    private static final String ANTHROPIC = """
            Task Management:
            You have access to the todo_write tool to help you manage and plan tasks. Use it VERY frequently to
            ensure you are tracking your tasks and giving the user visibility into your progress. It is also
            EXTREMELY helpful for planning, and for breaking down larger complex tasks into smaller steps; if you
            do not use it when planning you may forget to do important tasks - and that is unacceptable.
            It is critical that you mark todos as completed as soon as you are done with a task. Do not batch up
            multiple tasks before marking them as completed, and work through the whole list to completion.""";

    private static final String GEMINI = """
            Task Management:
            Use the todo_write tool to keep a structured task list for multi-step work. Mark each item in_progress
            as you start it and completed as soon as it is really done; keep a single in_progress item and do not
            batch completion updates. Prefer writing a todo when the request involves several distinct steps.""";

    private static final List<Family> FAMILIES = List.of(
            new Family("anthropic", List.of("anthropic", "claude"), ANTHROPIC),
            new Family("gemini", List.of("google", "gemini", "generativelanguage"), GEMINI));

    /**
     * Resolves the effective todo-guidance appendix for {@code todoMode} against {@code baseUrl},
     * or an empty string when the guidance is disabled.
     *
     * @param todoMode the configured mode ('auto', a verbatim appendix, or blank/'false' to disable).
     * @param baseUrl  the provider base URL used for family detection in 'auto' mode.
     * @return the guidance block to append to the system prompt, or an empty string for none.
     */
    public String resolve(final String todoMode, final String baseUrl) {
        final var mode = todoMode == null ? "auto" : todoMode;
        if (mode.isBlank() || mode.trim().equalsIgnoreCase("false")) {
            return "";
        }
        if (!mode.equalsIgnoreCase("auto")) {
            return mode;
        }
        for (final var family : FAMILIES) {
            if (family.matches(baseUrl)) {
                return family.block();
            }
        }
        return OPENAI;
    }
}