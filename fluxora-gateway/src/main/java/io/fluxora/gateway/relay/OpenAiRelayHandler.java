package io.fluxora.gateway.relay;
import io.vertx.core.json.JsonObject;
import io.vertx.core.buffer.Buffer;
/** OpenAI 原生 JSON 仅替换 model，不删除未知合法字段。 */
public final class OpenAiRelayHandler implements RelayHandler {
 public JsonObject upstreamRequest(JsonObject inbound,String upstream){return inbound.copy().put("model",upstream);}
 public JsonObject clientResponse(JsonObject upstream,String tenant){return upstream.copy().put("model",tenant);}
 public JsonObject clientSseData(JsonObject upstream,String tenant){JsonObject response=upstream.copy(); if(response.containsKey("model"))response.put("model",tenant); return response;}
 /** prompt_tokens 含缓存读取时，普通输入必须扣除 cached_tokens，避免两个桶重复计费。 */
 public RelayUsage extractUsage(JsonObject upstream){
  JsonObject usage=upstream.getJsonObject("usage"); if(usage==null)return RelayUsage.unknown();
  Long prompt=number(usage,"prompt_tokens"); Long completion=number(usage,"completion_tokens");
  JsonObject details=usage.getJsonObject("prompt_tokens_details"); Long cached=details==null?null:number(details,"cached_tokens");
  if(prompt!=null&&cached!=null&&(cached<0||cached>prompt))return RelayUsage.from(null,completion,null,null);
  return RelayUsage.from(prompt==null?null:prompt-(cached==null?0:cached),completion,null,cached);
 }
 private static Long number(JsonObject source,String field){Object value=source.getValue(field);return value instanceof Number n&&n.longValue()>=0?n.longValue():null;}
 public JsonObject error(String message){return new JsonObject().put("error",new JsonObject().put("message",message).put("type","api_error").putNull("param").putNull("code"));}
 public Buffer streamError(String message){return Buffer.buffer("data: "+error(message).encode()+"\n\ndata: [DONE]\n\n");}
 public String endpoint(){return "/v1/chat/completions";}
}
