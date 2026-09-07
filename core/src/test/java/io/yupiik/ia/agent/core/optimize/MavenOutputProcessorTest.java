package io.yupiik.ia.agent.core.optimize;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenOutputProcessorTest {
    private final MavenOutputProcessor processor = new MavenOutputProcessor();

    @Test
    void supportsMavenOnly() {
        assertTrue(processor.supports("mvn clean install"));
        assertTrue(processor.supports("mvnd test"));
        assertFalse(processor.supports("git status"));
    }

    @Test
    void collapsesBuildOutput() {
        final var out = processor.process("mvn clean install", List.of(
                "[INFO] Scanning for projects...",
                "[INFO] Building yca-core 1.0.0-SNAPSHOT",
                "[INFO] Running io.my.AppTest",
                "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0",
                "[INFO] Running io.my.BrokenTest",
                "[INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0",
                "[ERROR] cannot find symbol",
                "[WARNING] deprecated option",
                "[INFO] BUILD FAILURE",
                "[INFO] Total time:  01:02 min").stream()).toList();

        assertEquals("[INFO] BUILD FAILURE", out.get(0));
        assertTrue(out.contains("Errors (1):"));
        assertTrue(out.contains("[ERROR] cannot find symbol"));
        assertTrue(out.contains("Failing tests:"));
        assertTrue(out.stream().anyMatch(l -> l.contains("FAIL io.my.BrokenTest")));
        assertTrue(out.contains("Warnings (1):"));
        assertTrue(out.contains("Passing tests (1): io.my.AppTest"));
        assertFalse(out.contains("Scanning for projects..."));
        assertFalse(out.contains("Building yca-core"));
    }

    @Test
    void dropsNoiseLines() {
        final var out = processor.process("mvn install", List.of(
                "[INFO] Downloading from central: https://repo.maven.apache.org/maven2/x.jar",
                "[INFO] Downloaded from central: https://repo.maven.apache.org/maven2/x.jar",
                "[INFO] Progress (1): 12 kB",
                "[INFO]",
                "[INFO] BUILD SUCCESS").stream()).toList();

        assertEquals(1, out.size());
        assertEquals("[INFO] BUILD SUCCESS", out.get(0));
    }
}
