package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelsResponseTest {
    private final JsonMapper mapper = new JsonMapperImpl(List.of(
            new ModelsResponse$FusionJsonCodec(),
            new ModelsResponse$Model$FusionJsonCodec()), key -> Optional.empty());

    @Test
    void flatVendorKeyIsRead() {
        final var res = mapper.fromString(ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\",\"context_window\":8192}]}");
        assertEquals(8192, res.data().get(0).contextWindow());
    }

    @Test
    void llamaCppMetaNCtxIsRead() {
        final var res = mapper.fromString(ModelsResponse.class, """
                {"models":[{"name":"qwen3.8-27b","model":"qwen3.8-27b","modified_at":"","size":"","digest":"","type":"model","description":"","tags":[""],"capabilities":["completion"],"parameters":"","details":{"parent_model":"","format":"gguf","family":"","families":[""],"parameter_size":"","quantization_level":""}}],"object":"list","data":[{"id":"qwen3.8-27b","aliases":["qwen3.8-27b"],"tags":[],"object":"model","created":1788896358,"owned_by":"llamacpp","meta":{"vocab_type":2,"n_vocab":248320,"n_ctx":196608,"n_ctx_train":262144,"n_embd":5120,"n_params":27320697856,"size":16453443584,"ftype":"Q4_K - Medium"}}]}""");
        assertEquals(196608, res.data().get(0).contextWindow());
    }

    @Test
    void nCtxTrainWinsOnlyWhenSmallerThanDefault() {
        final var small = mapper.fromString(ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":4096}}]}");
        assertEquals(4096, small.data().get(0).contextWindow());
        final var big = mapper.fromString(ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":262144}}]}");
        assertNull(big.data().get(0).contextWindow());
    }

    @Test
    void noVendorDataIsNull() {
        final var res = mapper.fromString(ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\"}]}");
        assertNull(res.data().get(0).contextWindow());
    }

    @Test
    void flatKeysHavePriorityOverNested() {
        final var res = mapper.fromString(ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"context_length\":4096,\"meta\":{\"n_ctx\":8192}}]}");
        assertEquals(4096, res.data().get(0).contextWindow());
    }
}
