package io.fluxora.gateway.relay;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.GatewayRuntime;
import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.credential.RuntimeCredentialResolver;
import io.fluxora.gateway.route.RouteSelection;
import io.fluxora.gateway.transport.UpstreamHttpClient;
import io.fluxora.gateway.transport.UpstreamUrlValidator;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** 统一中继编排：鉴权和选路复用现有能力，协议分支只在此处的 switch 存在。 */
public final class RelayService {
    private final GatewayRuntime runtime; private final UpstreamHttpClient client; private final String profile;
    private final RelayHandler openAi = new OpenAiRelayHandler(); private final RelayHandler anthropic = new AnthropicRelayHandler();
    public RelayService(GatewayRuntime runtime, UpstreamHttpClient client, String profile){this.runtime=runtime;this.client=client;this.profile=profile;}
    public Future<Void> relay(HttpServerRequest request, String inboundProtocol, Buffer body) {
        JsonObject inbound; try { inbound=new JsonObject(body); } catch (RuntimeException e) { return Future.failedFuture(GatewayFailure.modelUnavailable()); }
        String model=inbound.getString("model"); if(model==null||model.isBlank()) return Future.failedFuture(GatewayFailure.modelUnavailable());
        return runtime.authenticator().authenticate(apiKey(request)).compose(p -> runtime.routeResolver().resolve(p.tenantId(), inboundProtocol, model, inbound.getBoolean("stream",false))
                .compose(s -> forward(request,p,inboundProtocol,model,inbound,s))).recover(e -> Future.failedFuture(e));
    }
    private Future<Void> forward(HttpServerRequest request, AuthenticatedPrincipal principal, String protocol, String tenantModel, JsonObject inbound, RouteSelection selection){
        if(!protocol.equals(selection.outboundProtocol())) return Future.failedFuture(GatewayFailure.modelUnavailable());
        RelayHandler handler=switch(protocol){case "OPENAI"->openAi;case "ANTHROPIC"->anthropic;default->throw GatewayFailure.unsupported();};
        JsonArray refs=selection.target().getJsonArray("credentialRefs"); if(refs==null||refs.isEmpty()) return Future.failedFuture(GatewayFailure.modelUnavailable());
        JsonObject ref=refs.getJsonObject(0);
        return runtime.credentialResolver().resolve(principal.tenantId(),ref.getLong("providerCredentialId"),ref.getLong("credentialVersion"),ref.getString("authType")).compose(c->{
            MultiMap headers=MultiMap.caseInsensitiveMultiMap(); headers.set("content-type","application/json"); headers.set("accept", request.getHeader("accept") == null ? "application/json" : request.getHeader("accept"));
            if("ANTHROPIC".equals(protocol)){ copy(request,headers,"anthropic-version"); copy(request,headers,"anthropic-beta"); }
            RuntimeCredentialResolver.applyAuthentication(headers,c);
            String url=UpstreamUrlValidator.endpoint(selection.target().getString("baseUrl"),handler.endpoint(),profile);
            return client.post(url,headers,Buffer.buffer(handler.upstreamRequest(inbound,selection.upstreamModelId()).encode()),selection.target().getInteger("connectTimeoutMs",5000),selection.target().getInteger("readTimeoutMs",60000))
                    .compose(x->write(request.response(),x,handler,tenantModel));
        });
    }
    private Future<Void> write(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange, RelayHandler handler,String tenantModel){
        if(exchange.response().statusCode()<200||exchange.response().statusCode()>=300) return Future.failedFuture(GatewayFailure.modelUnavailable());
        String type=exchange.response().getHeader("content-type");
        if(type!=null&&type.contains("text/event-stream")){ downstream.setStatusCode(200).putHeader("content-type","text/event-stream");
            exchange.response().handler(chunk->downstream.write(chunk)).endHandler(v->downstream.end()).exceptionHandler(e->downstream.end());
            downstream.closeHandler(v->exchange.request().cancel()); return Future.succeededFuture(); }
        return exchange.response().body().compose(buffer->{ try{return downstream.setStatusCode(200).putHeader("content-type","application/json").end(Buffer.buffer(handler.clientResponse(new JsonObject(buffer),tenantModel).encode()));}catch(RuntimeException e){return Future.failedFuture(GatewayFailure.modelUnavailable());}});
    }
    private static void copy(HttpServerRequest r,MultiMap h,String n){String v=r.getHeader(n);if(v!=null)h.set(n,v);}
    private static String apiKey(HttpServerRequest r){String a=r.getHeader("Authorization");return a!=null&&a.regionMatches(true,0,"Bearer ",0,7)?a.substring(7):r.getHeader("x-api-key");}
}
