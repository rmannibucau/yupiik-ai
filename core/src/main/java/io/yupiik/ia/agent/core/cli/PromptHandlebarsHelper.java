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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.handlebars.helper.HelperContext;

/**
 * A callable helper made available to the CLI prompt handlebars template. Each {@code @ApplicationScoped} bean
 * implementing this interface is registered under its {@link #name()} and can be invoked from the template.
 *
 * <p>The returned value is used as-is (inline and sub-expression calls are HTML-escaped by the engine, block calls
 * are not), so helpers render plain text or pre-formatted ANSI accordingly.</p>
 */
public interface PromptHandlebarsHelper {
    /** The helper name usable in the template, e.g. {@code {{time}}}. */
    String name();

    /** A short description used to document the helper. */
    String description();

    /**
     * Applies the helper.
     *
     * @param context the evaluated positional arguments, the {@code key=value} hash and the block/inverse renderers.
     * @return the helper output (may be null to render nothing).
     */
    Object apply(HelperContext context);
}
