package io.fluxora.gateway.relay;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 用量归一化回归：协议字段必须在 Gateway 集中拆分，未知桶始终保留为 null，
 * 避免 Platform 把未上报的 Token 当成零金额处理。
 */
class RelayUsageTest {

    @Test
    void openAiUsageMustSeparateCachedPromptTokensFromNormalInput() {
        RelayUsage usage = new OpenAiRelayHandler().extractUsage(new JsonObject()
                .put("usage", new JsonObject()
                        .put("prompt_tokens", 120)
                        .put("completion_tokens", 30)
                        .put("prompt_tokens_details", new JsonObject().put("cached_tokens", 20))));

        assertEquals(100L, usage.inputTokens());
        assertEquals(30L, usage.outputTokens());
        assertEquals(20L, usage.cacheReadTokens());
        assertNull(usage.cacheWriteTokens());
        assertEquals(RelayUsageStatus.REPORTED, usage.status());
    }

    @Test
    void missingUsageMustRemainUnknownInsteadOfSyntheticZeros() {
        RelayUsage usage = new OpenAiRelayHandler().extractUsage(new JsonObject().put("id", "chatcmpl-1"));

        assertNull(usage.inputTokens());
        assertNull(usage.outputTokens());
        assertNull(usage.cacheWriteTokens());
        assertNull(usage.cacheReadTokens());
        assertEquals(RelayUsageStatus.UNKNOWN, usage.status());
    }

    @Test
    void anthropicUsageMustKeepEachCacheBucketIndependent() {
        RelayUsage usage = new AnthropicRelayHandler().extractUsage(new JsonObject()
                .put("usage", new JsonObject()
                        .put("input_tokens", 100)
                        .put("output_tokens", 40)
                        .put("cache_creation_input_tokens", 12)
                        .put("cache_read_input_tokens", 18)));

        assertEquals(100L, usage.inputTokens());
        assertEquals(40L, usage.outputTokens());
        assertEquals(12L, usage.cacheWriteTokens());
        assertEquals(18L, usage.cacheReadTokens());
        assertEquals(RelayUsageStatus.REPORTED, usage.status());
    }

    @Test
    void anthropicSseUsageMustMergeMessageStartAndMessageDelta() {
        RelayUsageCollector collector = new RelayUsageCollector(new AnthropicRelayHandler());
        collector.accept(new JsonObject()
                .put("type", "message_start")
                .put("message", new JsonObject().put("usage", new JsonObject()
                        .put("input_tokens", 80)
                        .put("cache_creation_input_tokens", 10)
                        .put("cache_read_input_tokens", 20))));
        collector.accept(new JsonObject()
                .put("type", "message_delta")
                .put("usage", new JsonObject().put("output_tokens", 25)));

        RelayUsage usage = collector.currentUsage();
        assertEquals(80L, usage.inputTokens());
        assertEquals(25L, usage.outputTokens());
        assertEquals(10L, usage.cacheWriteTokens());
        assertEquals(20L, usage.cacheReadTokens());
        assertEquals(RelayUsageStatus.REPORTED, usage.status());
    }
}
