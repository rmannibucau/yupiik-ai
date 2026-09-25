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
package io.yupiik.ia.agent.core.llm;

/**
 * Per-session model selection (model, variant and sampling overrides). One
 * instance belongs to one tab/session and is passed explicitly to the
 * {@link ChatClient} calls made by that session's turn, so concurrent sessions
 * never see each other's selection. Plain state holder: no scope, no
 * thread-local, no DI.
 */
public class ChatSettings {
    private volatile String modelOverride;
    private volatile String variantOverride;
    private volatile ChatClient.SamplingOverride samplingOverride;

    /** The explicitly selected model, or {@code null} when the provider default applies. */
    public String model() {
        return modelOverride;
    }

    /**
     * Select a model for this session. Blank resets to the provider default and
     * drops the sampling override (it belonged to the previous model).
     */
    public void setModel(final String model) {
        this.modelOverride = model == null || model.isBlank() ? null : model;
        this.samplingOverride = null;
    }

    /** The selected variant name, or null when none is forced. */
    public String variant() {
        return variantOverride;
    }

    /** Select a named runtime variant for this session. Blank clears the selection. */
    public void setVariant(final String variant) {
        this.variantOverride = variant == null || variant.isBlank() ? null : variant;
    }

    /** The in-memory sampling overrides of this session, or null when none are set. */
    public ChatClient.SamplingOverride sampling() {
        return samplingOverride;
    }

    /** Replace the in-memory sampling overrides. A null (or empty) override clears them all. */
    public void setSampling(final ChatClient.SamplingOverride override) {
        this.samplingOverride = override == null || override.isEmpty() ? null : override;
    }

    /** Drops every selection, e.g. after a provider switch. */
    public void reset() {
        this.modelOverride = null;
        this.variantOverride = null;
        this.samplingOverride = null;
    }
}
