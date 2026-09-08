package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.ia.agent.core.optimize.Optimizers.matchesHead;

@ApplicationScoped
public class NpmOutputProcessor implements OutputProcessor {

    @Override
    public boolean supports(final String command) {
        return matchesHead(command, "npm", "pnpm");
    }

    @Override
    public int order() {
        return 14;
    }

    @Override
    public Set<String> names() {
        return Set.of("npm", "pnpm");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var out = new ArrayList<String>();
        for (final var raw : lines.toList()) {
            final var line = Optimizers.stripAnsi(raw).strip();
            if (line.isEmpty() || line.startsWith("> ")) {
                continue;
            }
            if (line.startsWith("npm warn") || line.startsWith("npm notice") || line.startsWith("npm WARN")
                    || line.startsWith("pnpm warn") || line.startsWith("pnpm notice")) {
                continue;
            }
            out.add(line);
        }
        if (out.isEmpty()) {
            return Stream.of("ok");
        }
        return out.stream();
    }
}
