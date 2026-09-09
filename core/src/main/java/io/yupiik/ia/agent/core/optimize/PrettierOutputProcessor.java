package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.ia.agent.core.optimize.Optimizers.containsFlag;
import static io.yupiik.ia.agent.core.optimize.Optimizers.matchesHead;

@ApplicationScoped
public class PrettierOutputProcessor implements OutputProcessor {
    private static final int MAX_FILES = 20;

    @Override
    public boolean supports(final String command) {
        return matchesHead(command, "prettier");
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Set<String> names() {
        return Set.of("prettier");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(Optimizers::stripAnsi).toList();
        final var check = containsFlag(command, "--check", "-c");
        final var paths = all.stream()
                .filter(l -> !l.isBlank() && !l.contains("error") && !l.contains("Error") && !l.startsWith("["))
                .map(String::strip)
                .toList();
        if (all.stream().anyMatch(l -> l.contains("error") || l.contains("Error"))) {
            return all.stream();
        }
        if (paths.isEmpty()) {
            return Stream.of(check ? "all files already formatted" : "ok");
        }
        final var out = new ArrayList<String>();
        out.add((check ? paths.size() + " file(s) need formatting:" : "Formatted " + paths.size() + " file(s):"));
        final var cap = Math.min(paths.size(), MAX_FILES);
        for (var i = 0; i < cap; i++) {
            out.add("  " + paths.get(i));
        }
        if (paths.size() > cap) {
            out.add("  ... +" + (paths.size() - cap) + " more");
        }
        return out.stream();
    }
}
