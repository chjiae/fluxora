package io.fluxora.gateway.relay;
import io.vertx.core.json.JsonObject;
import io.vertx.core.buffer.Buffer;
/** Anthropic 原生 JSON 仅替换 model，不进行跨协议字段转换。 */
public final class AnthropicRelayHandler implements RelayHandler {
 public JsonObject upstreamRequest(JsonObject inbound,String upstream){return inbound.copy().put("model",upstream);}
 public JsonObject clientResponse(JsonObject upstream,String tenant){return upstream.copy().put("model",tenant);}
 public JsonObject clientSseData(JsonObject upstream,String tenant){JsonObject response=upstream.copy(); if(response.containsKey("model"))response.put("model",tenant); JsonObject message=response.getJsonObject("message"); if(message!=null&&message.containsKey("model"))message.put("model",tenant); return response;}
 public JsonObject error(String message){return new JsonObject().put("type","error").put("error",new JsonObject().put("type","api_error").put("message",message));}
 public Buffer streamError(String message){return Buffer.buffer("event: error\ndata: "+error(message).encode()+"\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");}
 public String endpoint(){return "/v1/messages";}
}
