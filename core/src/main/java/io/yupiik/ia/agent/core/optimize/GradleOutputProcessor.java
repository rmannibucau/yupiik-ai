package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.ia.agent.core.optimize.Optimizers.containsFlag;
import static io.yupiik.ia.agent.core.optimize.Optimizers.matchesHead;

@ApplicationScoped
public class GradleOutputProcessor implements OutputProcessor {

    @Override
    public boolean supports(final String command) {
        return matchesHead(command, "gradle", "gradlew");
    }

    @Override
    public int order() {
        return 11;
    }

    @Override
    public Set<String> names() {
        return Set.of("gradle", "gradlew");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        if (containsFlag(command, "--info", "--debug", "--stacktrace", "--scan")) {
            return lines;
        }
        final var failures = new ArrayList<String>();
        final var warnings = new ArrayList<String>();
        String lastTask = null;
        String buildResult = null;
        var inFailure = false;

        for (final var raw : lines.toArray(String[]::new)) {
            final var line = Optimizers.stripAnsi(raw);
            if (line.startsWith("Download ")) {
                continue;
            }
            if (line.startsWith("BUILD SUCCESSFUL")) {
                buildResult = line.strip();
                inFailure = false;
                continue;
            }
            if (line.startsWith("BUILD FAILED") || line.startsWith("FAILURE:") || line.startsWith("* What went wrong:")) {
                buildResult = line.strip();
                inFailure = true;
                continue;
            }
            if (line.startsWith("> Task ")) {
                lastTask = line.strip();
                inFailure = line.contains("FAILED");
                if (inFailure) {
                    failures.add(lastTask);
                }
                continue;
            }
            if (inFailure) {
                if (line.isBlank() && !failures.isEmpty() && failures.get(failures.size() - 1).isBlank()) {
                    inFailure = false;
                    continue;
                }
                failures.add(line.strip());
                continue;
            }
            if (line.startsWith("Deprecated ") || (line.contains("warning") && !line.isBlank())) {
                if (warnings.size() < 10) {
                    warnings.add(line.strip());
                }
            }
        }

        final var out = new ArrayList<String>();
        if (buildResult != null) {
            out.add(buildResult);
        }
        if (!failures.isEmpty()) {
            final var cap = Math.min(failures.size(), 30);
            for (var i = 0; i < cap; i++) {
                out.add(failures.get(i));
            }
            if (failures.size() > cap) {
                out.add("... +" + (failures.size() - cap) + " more failure lines");
            }
        }
        if (!warnings.isEmpty()) {
            out.add("Warnings (" + warnings.size() + "):");
            warnings.forEach(w -> out.add("  " + w));
        }
        if (lastTask != null) {
            out.add("Last task: " + lastTask);
        }
        return out.stream();
    }
}
