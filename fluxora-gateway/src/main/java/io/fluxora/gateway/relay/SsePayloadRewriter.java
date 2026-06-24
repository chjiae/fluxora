package io.fluxora.gateway.relay;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import java.util.function.Consumer;

/**
 * 按 SSE 行边界改写 JSON data 事件，避免将上游模型标识暴露给客户端。
 * 上游 TCP 分块不保证与 SSE 行边界一致，因此需保留未结束的尾行。
 */
final class SsePayloadRewriter {
    private final RelayHandler handler;
    private final String tenantModelCode;
    private final Consumer<JsonObject> eventConsumer;
    private Buffer pending = Buffer.buffer();
    private boolean terminalEvent;

    SsePayloadRewriter(RelayHandler handler, String tenantModelCode) {
        this(handler, tenantModelCode, ignored -> { });
    }

    /**
     * 观察回调只接收单个已解析 data JSON，用于提取 usage；它不会保存文本内容，
     * 因而即使带有长文本或工具参数，也不会成为请求级聚合缓冲。
     */
    SsePayloadRewriter(RelayHandler handler, String tenantModelCode, Consumer<JsonObject> eventConsumer) {
        this.handler = handler;
        this.tenantModelCode = tenantModelCode;
        this.eventConsumer = eventConsumer;
    }

    Buffer rewrite(Buffer chunk) {
        pending.appendBuffer(chunk);
        Buffer rewritten = Buffer.buffer();
        int lineEnd;
        while ((lineEnd = lineFeedIndex(pending)) >= 0) {
            Buffer line = pending.getBuffer(0, lineEnd);
            pending = pending.getBuffer(lineEnd + 1, pending.length());
            appendLine(rewritten, line, true);
        }
        return rewritten;
    }

    Buffer finish() {
        if (pending.length() == 0) {
            return Buffer.buffer();
        }
        Buffer rewritten = Buffer.buffer();
        appendLine(rewritten, pending, false);
        pending = Buffer.buffer();
        return rewritten;
    }

    boolean hasTerminalEvent() {
        return terminalEvent;
    }

    private void appendLine(Buffer target, Buffer rawLine, boolean terminated) {
        boolean crlf = rawLine.length() > 0 && rawLine.getByte(rawLine.length() - 1) == '\r';
        String line = rawLine.getString(0, crlf ? rawLine.length() - 1 : rawLine.length());
        target.appendString(rewriteDataLine(line));
        if (crlf) {
            target.appendString("\r");
        }
        if (terminated) {
            target.appendString("\n");
        }
    }

    private String rewriteDataLine(String line) {
        if (!line.startsWith("data:")) {
            return line;
        }
        String data = line.substring("data:".length());
        String prefix = "data:";
        if (data.startsWith(" ")) {
            data = data.substring(1);
            prefix = "data: ";
        }
        if (data.isEmpty()) {
            return line;
        }
        if ("[DONE]".equals(data)) {
            terminalEvent = true;
            return line;
        }
        try {
            JsonObject event = new JsonObject(data);
            eventConsumer.accept(event);
            terminalEvent = terminalEvent || handler.isTerminalSseEvent(event);
            return prefix + handler.clientSseData(event, tenantModelCode).encode();
        } catch (RuntimeException ignored) {
            return line;
        }
    }

    private static int lineFeedIndex(Buffer source) {
        for (int index = 0; index < source.length(); index++) {
            if (source.getByte(index) == '\n') {
                return index;
            }
        }
        return -1;
    }
}
