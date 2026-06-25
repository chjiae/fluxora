package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.failure.ExecutionCertainty;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseCommitBarrierTest {

    @Test
    void openAiResponseIdCommitsAndBlocksRetry() {
        ResponseCommitBarrier barrier = ResponseCommitBarrier.openAi();

        CommitBarrierDecision decision = barrier.observeJsonEvent(new JsonObject()
                .put("id", "chatcmpl-safe-marker")
                .put("choices", new JsonArray().add(new JsonObject()
                        .put("delta", new JsonObject().put("content", "hello")))));

        assertTrue(decision.shouldCommit());
        assertFalse(barrier.snapshot().retrySafe());
        assertEquals(ExecutionCertainty.POSSIBLY_EXECUTED, barrier.snapshot().executionCertainty());
    }

    @Test
    void anthropicMessageStartCommitsAndBlocksRetry() {
        ResponseCommitBarrier barrier = ResponseCommitBarrier.anthropic();

        CommitBarrierDecision decision = barrier.observeJsonEvent(new JsonObject()
                .put("type", "message_start")
                .put("message", new JsonObject().put("id", "msg_123")));

        assertTrue(decision.shouldCommit());
        assertFalse(barrier.snapshot().retrySafe());
        assertEquals(ExecutionCertainty.POSSIBLY_EXECUTED, barrier.snapshot().executionCertainty());
    }

    @Test
    void firstStructuredErrorBeforeExecutionIsHeldForRetry() {
        ResponseCommitBarrier barrier = ResponseCommitBarrier.openAi();

        CommitBarrierDecision decision = barrier.observeJsonEvent(new JsonObject()
                .put("error", new JsonObject().put("code", "rate_limit_exceeded")));

        assertFalse(decision.shouldCommit());
        assertTrue(decision.failureSignal().isPresent());
        assertTrue(barrier.snapshot().retrySafe());
        assertEquals(ExecutionCertainty.PRE_EXECUTION_REJECTED, barrier.snapshot().executionCertainty());
    }

    @Test
    void bufferingLimitForcesCommitWithoutAggregatingFullResponse() {
        ResponseCommitBarrier barrier = ResponseCommitBarrier.openAi(2, 16);

        barrier.buffer(Buffer.buffer("data: {}\n\n"));
        CommitBarrierDecision decision = barrier.buffer(Buffer.buffer("data: {}\n\n"));

        assertTrue(decision.shouldCommit());
        assertEquals(ClientCommitState.COMMITTED, barrier.snapshot().clientCommitState());
    }
}
