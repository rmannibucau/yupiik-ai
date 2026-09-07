package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class GenericOutputProcessor implements OutputProcessor {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007");

    private final CodingAgentConfiguration configuration;

    public GenericOutputProcessor(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected GenericOutputProcessor() {
        this.configuration = null;
    }

    @Override
    public boolean supports(final String command) {
        return true;
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(ANSI::matcher).map(m -> m.replaceAll("")).toList();
        final var max = Math.max(10, configuration.optimize().genericMaxLines());
        if (all.size() <= max) {
            return stripBlankTail(all).stream();
        }
        final var head = (int) (max * 0.6);
        final var tail = max - head;
        final var out = new ArrayList<String>(max + 1);
        out.addAll(all.subList(0, head));
        out.add("[... " + (all.size() - head - tail) + " lines truncated ...]");
        out.addAll(all.subList(all.size() - tail, all.size()));
        return out.stream();
    }

    private List<String> stripBlankTail(final List<String> lines) {
        var lastMeaningful = -1;
        for (var i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                lastMeaningful = i;
            }
        }
        return lastMeaningful < 0 ? List.of() : new ArrayList<>(lines.subList(0, lastMeaningful + 1));
    }
}
