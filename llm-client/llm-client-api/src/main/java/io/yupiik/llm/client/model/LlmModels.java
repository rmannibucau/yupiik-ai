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
package io.yupiik.llm.client.model;

import java.util.List;

/**
 * Neutral model listing from a {@code /models} endpoint.
 *
 * @param data the listed models.
 */
public record LlmModels(List<LlmModel> data) {

    /** Defaults {@code data} to an empty list when absent. */
    public LlmModels {
        data = data == null ? List.of() : data;
    }

    /**
     * The model ids.
     *
     * @return the model ids, empty when none.
     */
    public List<String> ids() {
        return data.stream().map(LlmModel::id).toList();
    }
}
