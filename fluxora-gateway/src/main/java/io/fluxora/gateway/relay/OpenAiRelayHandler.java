package io.fluxora.gateway.relay;
import io.vertx.core.json.JsonObject;
/** OpenAI 原生 JSON 仅替换 model，不删除未知合法字段。 */
public final class OpenAiRelayHandler implements RelayHandler {
 public JsonObject upstreamRequest(JsonObject inbound,String upstream){return inbound.copy().put("model",upstream);}
 public JsonObject clientResponse(JsonObject upstream,String tenant){return upstream.copy().put("model",tenant);}
 public JsonObject error(String message){return new JsonObject().put("error",new JsonObject().put("message",message).put("type","api_error").putNull("param").putNull("code"));}
 public String endpoint(){return "/v1/chat/completions";}
}
