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
package io.yupiik.ia.agent.core.shell.slash;

/**
 * Kind of argument completion offered by a slash command. The enumeration is
 * intentionally data-only: each shell maps it to its own completion UI
 * (JLine candidates, TUI popup, ...), there is no central hardcoded mapping
 * left.
 */
public enum ArgCompletion {
    NONE,
    MODELS,
    PROVIDERS,
    AGENTS,
    SESSIONS,
    TOOLS,
    /** Fixed "build|plan" options. */
    MODE,
    /** Fixed "all|none|show" options. */
    PERMISSIONS,
    /** Fixed "show|hide" options. */
    SHOW_HIDE,
    /** Filesystem path. */
    PATH
}
