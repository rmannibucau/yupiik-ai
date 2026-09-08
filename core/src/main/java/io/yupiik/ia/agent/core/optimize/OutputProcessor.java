package io.yupiik.ia.agent.core.optimize;

import java.util.Set;
import java.util.stream.Stream;

public interface OutputProcessor {

    boolean supports(String command);

    Stream<String> process(String command, Stream<String> lines);

    Set<String> names();

    default String displayName() {
        final var simple = getClass().getSimpleName();
        final var fusion = simple.indexOf("$Fusion");
        return fusion < 0 ? simple : simple.substring(0, fusion);
    }

    default int order() {
        return 100;
    }
}
