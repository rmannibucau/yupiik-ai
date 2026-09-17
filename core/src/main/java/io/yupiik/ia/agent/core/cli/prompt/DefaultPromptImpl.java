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
package io.yupiik.ia.agent.core.cli.prompt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Degraded {@link Prompt} used when the terminal widget stack is not present:
 * it reads plain lines from {@code stdin} and does not offer line editing.
 * <p>
 * Nothing is closed here: {@code stdin}/{@code stdout} are process-owned and
 * must outlive this bean.
 */
@ApplicationScoped
public class DefaultPromptImpl implements Prompt {
    private final BufferedReader reader;
    private final PrintStream out;

    public DefaultPromptImpl() {
        this(System.in, System.out);
    }

    DefaultPromptImpl(final InputStream in, final PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    @Override
    public String readLine(final String prompt) throws IOException {
        out.print(prompt);
        out.flush();
        return reader.readLine();
    }
}
