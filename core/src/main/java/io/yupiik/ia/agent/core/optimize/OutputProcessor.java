package io.yupiik.ia.agent.core.optimize;

import java.util.stream.Stream;

public interface OutputProcessor {

    boolean supports(String command);

    Stream<String> process(String command, Stream<String> lines);

    default int order() {
        return 100;
    }
}
