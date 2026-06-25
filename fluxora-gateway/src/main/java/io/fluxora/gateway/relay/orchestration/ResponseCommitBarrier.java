package io.fluxora.gateway.relay.orchestration;

import io.fluxora.gateway.relay.failure.UpstreamSignal;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 响应提交屏障。
 *
 * 只判断“是否可以向客户端提交响应”和“是否出现预执行结构化错误”，不决定重试、不选上游、
 * 不写 Redis。缓冲只保留有限事件/字节，防止把完整响应聚合进内存。
 */
public final class ResponseCommitBarrier {
    private final String protocol;
    private final int maxBufferedEvents;
    private final int maxBufferedBytes;
    private int bufferedEvents;
    private int bufferedBytes;
    private AttemptStateSnapshot snapshot = AttemptStateSnapshot.initial();

    private ResponseCommitBarrier(String protocol, int maxBufferedEvents, int maxBufferedBytes) {
        this.protocol = protocol;
        this.maxBufferedEvents = maxBufferedEvents;
        this.maxBufferedBytes = maxBufferedBytes;
    }

    public static ResponseCommitBarrier openAi() { return openAi(32, 64 * 1024); }
    public static ResponseCommitBarrier anthropic() { return anthropic(32, 64 * 1024); }
    public static ResponseCommitBarrier openAi(int maxEvents, int maxBytes) { return new ResponseCommitBarrier("OPENAI", maxEvents, maxBytes); }
    public static ResponseCommitBarrier anthropic(int maxEvents, int maxBytes) { return new ResponseCommitBarrier("ANTHROPIC", maxEvents, maxBytes); }

    public CommitBarrierDecision observeJsonEvent(JsonObject event) {
        if (event == null) return CommitBarrierDecision.hold();
        if (isStructuredError(event)) {
            snapshot = snapshot.preExecutionRejected();
            return CommitBarrierDecision.failure(UpstreamSignal.sse(protocol, event));
        }
        if (containsExecutionMarker(event)) {
            snapshot = snapshot.possiblyExecuted().committed();
            return CommitBarrierDecision.commit();
        }
        return CommitBarrierDecision.hold();
    }

    public CommitBarrierDecision buffer(Buffer chunk) {
        bufferedEvents++;
        bufferedBytes += chunk == null ? 0 : chunk.length();
        if (bufferedEvents >= maxBufferedEvents || bufferedBytes >= maxBufferedBytes) {
            snapshot = snapshot.committed();
            return CommitBarrierDecision.commit();
        }
        return CommitBarrierDecision.hold();
    }

    public AttemptStateSnapshot snapshot() { return snapshot; }

    private boolean isStructuredError(JsonObject event) {
        return event.containsKey("error") || "error".equals(event.getString("type"));
    }

    private boolean containsExecutionMarker(JsonObject event) {
        if ("ANTHROPIC".equals(protocol)) {
            return "message_start".equals(event.getString("type"))
                    || event.containsKey("usage")
                    || hasNonBlank(event.getJsonObject("message"), "id");
        }
        if (hasNonBlank(event, "id") || event.containsKey("usage")) return true;
        JsonArray choices = event.getJsonArray("choices");
        if (choices == null || choices.isEmpty()) return false;
        for (int i = 0; i < choices.size(); i++) {
            JsonObject choice = choices.getJsonObject(i);
            JsonObject delta = choice == null ? null : choice.getJsonObject("delta");
            if (delta != null && (hasNonBlank(delta, "content") || delta.containsKey("tool_calls") || delta.containsKey("function_call"))) {
                return true;
            }
            if (choice != null && hasNonBlank(choice, "text")) return true;
        }
        return false;
    }

    private static boolean hasNonBlank(JsonObject object, String field) {
        return object != null && object.getString(field) != null && !object.getString(field).isBlank();
    }
}
