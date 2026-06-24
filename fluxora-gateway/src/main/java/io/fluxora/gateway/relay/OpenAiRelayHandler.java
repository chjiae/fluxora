package io.fluxora.gateway.relay;
import io.vertx.core.json.JsonObject;
import io.vertx.core.buffer.Buffer;
/** OpenAI 原生 JSON 仅替换 model，不删除未知合法字段。 */
public final class OpenAiRelayHandler implements RelayHandler {
 public JsonObject upstreamRequest(JsonObject inbound,String upstream){return inbound.copy().put("model",upstream);}
 public JsonObject clientResponse(JsonObject upstream,String tenant){return upstream.copy().put("model",tenant);}
 public JsonObject clientSseData(JsonObject upstream,String tenant){JsonObject response=upstream.copy(); if(response.containsKey("model"))response.put("model",tenant); return response;}
 public JsonObject error(String message){return new JsonObject().put("error",new JsonObject().put("message",message).put("type","api_error").putNull("param").putNull("code"));}
 public Buffer streamError(String message){return Buffer.buffer("data: "+error(message).encode()+"\n\ndata: [DONE]\n\n");}
 public String endpoint(){return "/v1/chat/completions";}
}
