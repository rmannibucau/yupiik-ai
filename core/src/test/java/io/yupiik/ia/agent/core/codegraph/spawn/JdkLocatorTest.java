/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.codegraph.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkLocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void prefersJdkHomeOverPathAndDeDuplicates() throws Exception {
        // forge a fake launcher layout to avoid depending on the machine PATH
        final var jdkHome = tempDir.resolve("home");
        final var path1 = tempDir.resolve("p1");
        final var path2 = tempDir.resolve("p2");
        Files.createDirectories(jdkHome.resolve("bin"));
        Files.createDirectories(path1);
        Files.createDirectories(path2);
        final var homeLauncher = jdkHome.resolve("bin/java");
        Files.writeString(homeLauncher, "#!/bin/sh\nexit 0\n");
        homeLauncher.toFile().setExecutable(true);
        final var path1Launcher = path1.resolve("java");
        Files.writeString(path1Launcher, "#!/bin/sh\nexit 0\n");
        path1Launcher.toFile().setExecutable(true);

        final var locator = new JdkLocator() {
            @Override
            String env(final String name) {
                if ("JAVA_HOME".equals(name)) {
                    return jdkHome.toString();
                }
                if ("PATH".equals(name)) {
                    return path1.toString() + java.io.File.pathSeparator + path2.toString();
                }
                return null;
            }
        };
        final var candidates = locator.candidates();
        assertEquals(2, candidates.size(), "home first then path, path2 skipped because not executable");
        assertEquals(homeLauncher, candidates.get(0), "JAVA_HOME launcher must come first");
        assertEquals(path1Launcher, candidates.get(1), "PATH launchers after JAVA_HOME");

        // probe against the real current JDK launcher -> must accept it
        final var realJavaHome = System.getenv("JAVA_HOME");
        assertNotNull(realJavaHome, "test expects JAVA_HOME to be set");
        final var locator2 = new JdkLocator() {
            @Override
            String env(final String name) {
                return "JAVA_HOME".equals(name) ? realJavaHome : null;
            }
        };
        assertNotNull(locator2.locate(), "locate must find a JDK");
        assertTrue(locator2.hasCompiler(locator2.locate()), "the found JDK must expose a compiler");
    }

    @Test
    void rejectsPlainJresAndBrokenLaunchers() throws Exception {
        // a launcher that cannot even start must be rejected, not trusted
        final var path1 = tempDir.resolve("broken");
        Files.createDirectories(path1);
        final var launcher = path1.resolve("java");
        Files.writeString(launcher, "#!/bin/sh\nexit 1\n");
        launcher.toFile().setExecutable(true);

        // suppress the superclass's environment reads by overriding: give it only this broken PATH entry
        final var locator = new JdkLocator() {
            @Override
            String env(final String name) {
                return "PATH".equals(name) ? path1.toString() : null;
            }
        };
        final var candidates = locator.candidates();
        assertEquals(1, candidates.size());
        assertFalse(locator.hasCompiler(candidates.get(0)), "a broken launcher must not be accepted as a JDK");
    }

    @Test
    void cachesResolvedJdk() {
        final var realJavaHome = System.getenv("JAVA_HOME");
        assertNotNull(realJavaHome);
        final var locator = new JdkLocator() {
            int calls;

            @Override
            String env(final String name) {
                return "JAVA_HOME".equals(name) ? realJavaHome : null;
            }

            @Override
            List<Path> candidates() {
                calls++;
                return super.candidates();
            }
        };
        final var first = locator.locate();
        final var second = locator.locate();
        assertNotNull(first);
        assertEquals(first, second, "locate must be single-flight/cached");
        assertEquals(1, locator.calls, "candidates() must be called only once");
    }
}
