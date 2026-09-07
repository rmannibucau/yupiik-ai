package io.yupiik.ia.agent.core.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ContextManager {
    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;
    private final UsageTracker usageTracker;

    public ContextManager(final CodingAgentConfiguration configuration, final ChatClient chatClient,
                          final UsageTracker usageTracker) {
        this.configuration = configuration;
        this.chatClient = chatClient;
        this.usageTracker = usageTracker;
    }

    protected ContextManager() {
        this.configuration = null;
        this.chatClient = null;
        this.usageTracker = null;
    }

    public boolean pruneNeeded(final List<Message> messages) {
        final var p = configuration.pruning();
        return p.enabled() && usageTracker.contextPercent(usageTracker.estimateContext(messages)) >= p.thresholdPercent();
    }

    public boolean compactNeeded(final List<Message> messages) {
        final var c = configuration.compaction();
        return c.enabled() && usageTracker.contextPercent(usageTracker.estimateContext(messages)) >= c.thresholdPercent();
    }

    public List<Message> prune(final List<Message> messages) {
        final var p = configuration.pruning();
        final var keep = Math.max(2, p.keepRecent());
        if (messages.size() <= keep + 2) {
            return messages;
        }
        final var system = messages.get(0).role().equals("system") ? messages.get(0) : null;
        final var from = system == null ? 0 : 1;
        final var to = Math.max(from, messages.size() - keep);
        if (to <= from) {
            return messages;
        }
        final var old = messages.subList(from, to);
        final var sb = new StringBuilder();
        for (final var m : old) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            if (content.length() > p.toolOutputMaxChars()) {
                sb.append(content, 0, p.toolOutputMaxChars()).append(" [...]\n");
            } else {
                sb.append(content).append('\n');
            }
        }
        final var digestModel = configuration.model().prune() == null || configuration.model().prune().isBlank()
                ? configuration.provider().model() : configuration.model().prune();
        final String digest;
        try {
            final var res = chatClient.chat(new ChatCompletionRequest(digestModel, List.of(
                    Message.system("Summarize this conversation excerpt into a compact digest. Keep key decisions, file paths, commands and results. Max 40 lines."),
                    Message.user(sb.toString())), null, false, null, null, null, null, null));
            digest = res.message() == null ? "" : str(res.message().content());
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return localTruncate(messages, from, to);
        }
        if (digest == null || digest.isBlank()) {
            return localTruncate(messages, from, to);
        }
        final var out = new ArrayList<Message>();
        if (system != null) {
            out.add(system);
        }
        out.add(Message.user("<pruned-context>\n" + digest + "\n</pruned-context>"));
        out.addAll(messages.subList(to, messages.size()));
        return out;
    }

    public List<Message> compact(final List<Message> messages) {
        final var keep = Math.max(2, configuration.pruning().keepRecent());
        if (messages.size() <= keep + 2) {
            return messages;
        }
        final var system = messages.get(0).role().equals("system") ? messages.get(0) : null;
        final var from = system == null ? 0 : 1;
        final var to = Math.max(from, messages.size() - keep);
        final var sb = new StringBuilder();
        for (final var m : messages.subList(from, to)) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            sb.append('[').append(m.role()).append("] ");
            if (content.length() > 2000) {
                sb.append(content, 0, 2000).append(" [...]");
            } else {
                sb.append(content);
            }
            sb.append('\n');
        }
        final var compactModel = configuration.model().compact() == null || configuration.model().compact().isBlank()
                ? configuration.provider().model() : configuration.model().compact();
        final String summary;
        try {
            final var res = chatClient.chat(new ChatCompletionRequest(compactModel, List.of(
                    Message.system("Summarize this coding agent conversation: goal, decisions, files touched, commands run, current state and next steps. Max 60 lines."),
                    Message.user(sb.toString())), null, false, null, null, null, null, null));
            summary = res.message() == null ? "" : str(res.message().content());
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return messages;
        }
        if (summary == null || summary.isBlank()) {
            return messages;
        }
        final var out = new ArrayList<Message>();
        if (system != null) {
            out.add(system);
        }
        out.add(Message.user("<compacted-context>\n" + summary + "\n</compacted-context>\n\nContinue from this summary."));
        out.addAll(messages.subList(to, messages.size()));
        return out;
    }

    private List<Message> localTruncate(final List<Message> messages, final int from, final int to) {
        final var out = new ArrayList<Message>();
        if (messages.get(0).role().equals("system")) {
            out.add(messages.get(0));
        }
        for (final var m : messages.subList(from, to)) {
            final var content = m.content() instanceof String s ? s : String.valueOf(m.content());
            final var cap = configuration.pruning().toolOutputMaxChars();
            out.add(new Message(m.role(), content.length() > cap ? content.substring(0, cap) + " [...]" : content,
                    m.name(), m.toolCalls(), m.toolCallId(), m.others()));
        }
        out.addAll(messages.subList(to, messages.size()));
        return out;
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }
}
