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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ModelsResponseTest {
    @Test
    void flatVendorKeyIsRead(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\",\"context_window\":8192}]}");
        assertEquals(8192, res.data().get(0).contextWindow());
    }

    @Test
    void llamaCppMetaNCtxIsRead(final JsonMapper mapper) {
        final var res = mapper.fromString(ModelsResponse.class, """
                {"models":[{"name":"qwen3.8-27b","model":"qwen3.8-27b","modified_at":"","size":"","digest":"","type":"model","description":"","tags":[""],"capabilities":["completion"],"parameters":"","details":{"parent_model":"","format":"gguf","family":"","families":[""],"parameter_size":"","quantization_level":""}}],"object":"list","data":[{"id":"qwen3.8-27b","aliases":["qwen3.8-27b"],"tags":[],"object":"model","created":1788896358,"owned_by":"llamacpp","meta":{"vocab_type":2,"n_vocab":248320,"n_ctx":196608,"n_ctx_train":262144,"n_embd":5120,"n_params":27320697856,"size":16453443584,"ftype":"Q4_K - Medium"}}]}""");
        assertEquals(196608, res.data().get(0).contextWindow());
    }

    @Test
    void nCtxTrainIsUsedAsBestEffortUpperBound(final JsonMapper mapper) {
        final var small =
                mapper.fromString(ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":4096}}]}");
        assertEquals(4096, small.data().get(0).contextWindow());
        final var big = mapper.fromString(
                ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":262144}}]}");
        assertEquals(262144, big.data().get(0).contextWindow());
    }

    @Test
    void noVendorDataIsNull(final JsonMapper mapper) {
        final var res = mapper.fromString(ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\"}]}");
        assertNull(res.data().get(0).contextWindow());
    }

    @Test
    void flatKeysHavePriorityOverNested(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"context_length\":4096,\"meta\":{\"n_ctx\":8192}}]}");
        assertEquals(4096, res.data().get(0).contextWindow());
    }

    @Test
    void sglangContextLengthAndMaxOutputLengthAreRead(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class,
                "{\"data\":[{\"id\":\"deepseek-v4-flash\",\"owned_by\":\"sglang\",\"context_length\":1000000,\"max_output_length\":393216}]}");
        assertEquals(1_000_000, res.data().get(0).contextWindow(), "context_length is a token total window");
        assertEquals(393_216, res.data().get(0).maxTokens(), "max_output_length is a token output cap");
    }
}
