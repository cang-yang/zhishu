package com.canggo.zhishu.client;

import com.canggo.zhishu.service.ModelProviderConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddingClientRequestContractTest {

    @Test
    void usesOpenAiCompatiblePluralDimensionsField() {
        ModelProviderConfigService.ActiveProviderView provider =
                new ModelProviderConfigService.ActiveProviderView(
                        "siliconflow",
                        "SiliconFlow",
                        "OPENAI_COMPATIBLE",
                        "https://api.siliconflow.cn/v1",
                        "Qwen/Qwen3-Embedding-4B",
                        "not-a-real-key",
                        2048
                );
        List<String> input = List.of("first chunk", "second chunk");

        Map<String, Object> request = EmbeddingClient.buildRequestBody(provider, input);

        assertEquals("Qwen/Qwen3-Embedding-4B", request.get("model"));
        assertEquals(input, request.get("input"));
        assertEquals(2048, request.get("dimensions"));
        assertEquals("float", request.get("encoding_format"));
        assertFalse(request.containsKey("dimension"));
    }
}
