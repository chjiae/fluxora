package io.fluxora.gateway.relay;
import io.vertx.core.json.JsonObject;
import io.vertx.core.buffer.Buffer;
/** Anthropic 原生 JSON 仅替换 model，不进行跨协议字段转换。 */
public final class AnthropicRelayHandler implements RelayHandler {
 public JsonObject upstreamRequest(JsonObject inbound,String upstream){return inbound.copy().put("model",upstream);}
 public JsonObject clientResponse(JsonObject upstream,String tenant){return upstream.copy().put("model",tenant);}
 public JsonObject clientSseData(JsonObject upstream,String tenant){JsonObject response=upstream.copy(); if(response.containsKey("model"))response.put("model",tenant); JsonObject message=response.getJsonObject("message"); if(message!=null&&message.containsKey("model"))message.put("model",tenant); return response;}
 /** Anthropic 非流式 usage 位于根对象；SSE 的 message_start 位于 message，message_delta 位于根对象。 */
 public RelayUsage extractUsage(JsonObject upstream){
  JsonObject usage=upstream.getJsonObject("usage");
  if(usage==null){JsonObject message=upstream.getJsonObject("message");usage=message==null?null:message.getJsonObject("usage");}
  if(usage==null)return RelayUsage.unknown();
  return RelayUsage.from(number(usage,"input_tokens"),number(usage,"output_tokens"),number(usage,"cache_creation_input_tokens"),number(usage,"cache_read_input_tokens"));
 }
 public boolean isTerminalSseEvent(JsonObject upstream){return "message_stop".equals(upstream.getString("type"));}
 private static Long number(JsonObject source,String field){Object value=source.getValue(field);return value instanceof Number n&&n.longValue()>=0?n.longValue():null;}
 public JsonObject error(String message){return new JsonObject().put("type","error").put("error",new JsonObject().put("type","api_error").put("message",message));}
 public Buffer streamError(String message){return Buffer.buffer("event: error\ndata: "+error(message).encode()+"\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");}
 public String endpoint(){return "/v1/messages";}
}
