package io.fluxora.gateway.relay;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 SSE 任意分块下的模型标识脱敏，不依赖真实网络或 Redis。 */
class SsePayloadRewriterTest {

    @Test
    void shouldRewriteOpenAiModelAfterDataLineIsSplitAcrossChunks() {
        SsePayloadRewriter rewriter = new SsePayloadRewriter(new OpenAiRelayHandler(), "tenant-qwen");

        assertEquals("", rewriter.rewrite(Buffer.buffer("data: {\"model\":\"upstream-")).toString());
        assertEquals(
                "data: {\"model\":\"tenant-qwen\",\"id\":\"chatcmpl-1\",\"choices\":[]}\n\n",
                rewriter.rewrite(Buffer.buffer("model\",\"id\":\"chatcmpl-1\",\"choices\":[]}\n\n")).toString());
        assertEquals("data: [DONE]\n\n", rewriter.rewrite(Buffer.buffer("data: [DONE]\n\n")).toString());
    }

    @Test
    void shouldRewriteAnthropicNestedMessageModelAndKeepEventFraming() {
        SsePayloadRewriter rewriter = new SsePayloadRewriter(new AnthropicRelayHandler(), "tenant-claude");

        Buffer actual = rewriter.rewrite(Buffer.buffer("event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"model\":\"upstream-model\"}}\n\n"));

        assertEquals("event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\",\"model\":\"tenant-claude\"}}\n\n", actual.toString());
    }

    @Test
    void shouldProduceProtocolNativeStreamErrors() {
        assertEquals("data: {\"error\":{\"message\":\"当前模型暂不可用，请稍后重试\",\"type\":\"api_error\",\"param\":null,\"code\":null}}\n\n"
                        + "data: [DONE]\n\n",
                new OpenAiRelayHandler().streamError("当前模型暂不可用，请稍后重试").toString());
        assertEquals("event: error\n"
                        + "data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"当前模型暂不可用，请稍后重试\"}}\n\n"
                        + "event: message_stop\n"
                        + "data: {\"type\":\"message_stop\"}\n\n",
                new AnthropicRelayHandler().streamError("当前模型暂不可用，请稍后重试").toString());
    }

    @Test
    void shouldRecognizeProtocolTerminalEventsWithoutRetainingSseBody() {
        SsePayloadRewriter openAi = new SsePayloadRewriter(new OpenAiRelayHandler(), "tenant-qwen");
        openAi.rewrite(Buffer.buffer("data: [DONE]\n\n"));
        assertEquals(true, openAi.hasTerminalEvent());

        SsePayloadRewriter anthropic = new SsePayloadRewriter(new AnthropicRelayHandler(), "tenant-claude");
        anthropic.rewrite(Buffer.buffer("event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n"));
        assertEquals(true, anthropic.hasTerminalEvent());
    }
}
