package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.ia.agent.core.optimize.Optimizers.matchesHead;

@ApplicationScoped
public class GhOutputProcessor implements OutputProcessor {

    @Override
    public boolean supports(final String command) {
        return matchesHead(command, "gh");
    }

    @Override
    public int order() {
        return 13;
    }

    @Override
    public Set<String> names() {
        return Set.of("gh");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var filtered = lines.filter(l -> !l.isBlank() || !isNoise(l));
        return filtered;
    }

    private boolean isNoise(final String line) {
        final var t = line.strip();
        return t.startsWith("[![") || t.startsWith("![") || t.startsWith("<!--") || t.endsWith("-->")
                || t.equals("---") || t.equals("***") || t.startsWith("<img ") || t.equals("<p align=\"center\">")
                || t.equals("</p>") || t.startsWith("<a href=");
    }
}
