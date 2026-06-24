package io.fluxora.gateway.relay;
import io.vertx.core.json.JsonObject;
/** Anthropic 原生 JSON 仅替换 model，不进行跨协议字段转换。 */
public final class AnthropicRelayHandler implements RelayHandler {
 public JsonObject upstreamRequest(JsonObject inbound,String upstream){return inbound.copy().put("model",upstream);}
 public JsonObject clientResponse(JsonObject upstream,String tenant){return upstream.copy().put("model",tenant);}
 public JsonObject error(String message){return new JsonObject().put("type","error").put("error",new JsonObject().put("type","api_error").put("message",message));}
 public String endpoint(){return "/v1/messages";}
}
