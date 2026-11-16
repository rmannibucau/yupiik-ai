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
package io.yupiik.ia.agent.bot.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit tool variables only, never inherited host environment or executor credentials. */
public final class ToolEnvironment {
    private ToolEnvironment() {}

    public static void validate(final Map<String, String> environment) {
        if (environment == null || environment.size() > 128) {
            throw new IllegalArgumentException("Invalid dependency cache environment");
        }
        final var reserved = Set.of(
                "HOME",
                "PATH",
                "USER",
                "LOGNAME",
                "SHELL",
                "PWD",
                "OLDPWD",
                "TMPDIR",
                "TMP",
                "TEMP",
                "ENV",
                "BASH_ENV",
                "IFS",
                "CLASSPATH",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "NODE_OPTIONS",
                "PYTHONPATH",
                "PYTHONHOME",
                "RUBYOPT",
                "PERL5OPT");
        environment.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("Null dependency cache environment name");
            }
            final var upper = key.toUpperCase(Locale.ROOT);
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || reserved.contains(upper)
                    || upper.startsWith("DOCKER_")
                    || upper.startsWith("LD_")
                    || upper.startsWith("DYLD_")
                    || upper.startsWith("YCA_")
                    || upper.startsWith("GITHUB_")
                    || upper.startsWith("GH_")
                    || upper.startsWith("GIT_")
                    || upper.startsWith("JAVA_")
                    || upper.startsWith("SSH_")
                    || upper.startsWith("KUBERNETES_")
                    || upper.matches(".*(TOKEN|PASSWORD|PASSWD|SECRET|CREDENTIAL|AUTH|CONTEXT|PROXY|KEY|COOKIE).*")) {
                throw new IllegalArgumentException("Unsafe dependency cache environment variable: " + key);
            }
            if (value == null
                    || value.length() > 65536
                    || value.indexOf('\0') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0
                    || value.matches("(?s).*://[^/\\s]*@.*")) {
                throw new IllegalArgumentException("Invalid dependency cache environment value");
            }
        });
    }
}
