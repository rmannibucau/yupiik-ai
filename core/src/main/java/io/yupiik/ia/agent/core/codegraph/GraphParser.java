package io.yupiik.ia.agent.core.codegraph;

import java.nio.file.Path;
import java.util.Set;

/**
 * SPI for structural parsers. Implementations are collected by the container (Fusion) and
 * aggregated by {@link StructuralCodeGraph}, which routes each source file to the parser that
 * handles its extension. Adding a new language only requires a new container bean implementing
 * this interface.
 */
public interface GraphParser {

    /**
     * @return the file extensions (without the leading dot) this parser handles, eg. {@code java}.
     */
    Set<String> extensions();

    /**
     * @return {@code true} when this parser needs a full JDK to operate (removing the check for a
     *         plain JRE), which the tooling must surface to the user.
     */
    default boolean jdkRequired() {
        return false;
    }

    /**
     * @return {@code true} when the parser can run in the current runtime. A parser may degrade
     *         gracefully (eg. return false when only a JRE is present) and be skipped.
     */
    default boolean available() {
        return true;
    }

    /**
     * Parse a single source file into a language-neutral model. Should be fast enough to call for a
     * single file during incremental re-indexing.
     */
    GraphModel parse(Path file);
}