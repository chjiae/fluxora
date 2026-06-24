package io.fluxora.gateway.relay;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.GatewayRuntime;
import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.credential.RuntimeCredentialResolver;
import io.fluxora.gateway.observability.RelayObservationEvent;
import io.fluxora.gateway.observability.RelayPriceSnapshot;
import io.fluxora.gateway.observability.RelayRequestId;
import io.fluxora.gateway.route.RouteSelection;
import io.fluxora.gateway.transport.UpstreamHttpClient;
import io.fluxora.gateway.transport.UpstreamUrlValidator;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.time.Instant;

/** 统一中继编排：鉴权和选路复用现有能力，协议分支只在此处的 switch 存在。 */
public final class RelayService {
    private final GatewayRuntime runtime; private final UpstreamHttpClient client; private final String profile;
    private static final String STREAM_UPSTREAM_ERROR = "当前模型暂不可用，请稍后重试";
    private final RelayHandler openAi = new OpenAiRelayHandler(); private final RelayHandler anthropic = new AnthropicRelayHandler();
    public RelayService(GatewayRuntime runtime, UpstreamHttpClient client, String profile){this.runtime=runtime;this.client=client;this.profile=profile;}
    public Future<Void> relay(HttpServerRequest request, String inboundProtocol, Buffer body) {
        JsonObject inbound; try { inbound=new JsonObject(body); } catch (RuntimeException e) { return Future.failedFuture(GatewayFailure.invalidRequest()); }
        String model=inbound.getString("model"); if(model==null||model.isBlank()) return Future.failedFuture(GatewayFailure.invalidRequest());
        return runtime.authenticator().authenticate(apiKey(request)).compose(p -> runtime.routeResolver()
                .resolve(p.tenantId(), inboundProtocol, model, inbound.getBoolean("stream", false)).compose(s -> {
                    RelayObservationEvent started = RelayObservationEvent.started(RelayRequestId.next(), p, inboundProtocol,
                            request.path(), inbound.getBoolean("stream", false), s.routeTargetId(), s.providerChannelId(),
                            s.providerChannelModelId(), RelayPriceSnapshot.fromRoute(s.routeSnapshot()));
                    // 仅发起异步投递，绝不等待 Redis；用户中继路径保持与此前相同的非阻塞特性。
                    runtime.relayEventPublisher().publish(started);
                    return forward(request, p, inboundProtocol, model, inbound, s, started)
                            .recover(error -> {
                                runtime.relayEventPublisher().publish(started.failed("UPSTREAM_UNAVAILABLE", null,
                                        RelayUsage.unknown(), elapsedMillis(started)));
                                return Future.failedFuture(error);
                            });
                })).recover(e -> Future.failedFuture(e));
    }
    private Future<Void> forward(HttpServerRequest request, AuthenticatedPrincipal principal, String protocol, String tenantModel,
                                 JsonObject inbound, RouteSelection selection, RelayObservationEvent observation){
        if(!protocol.equals(selection.outboundProtocol())) return Future.failedFuture(GatewayFailure.modelUnavailable());
        RelayHandler handler=handlerFor(protocol);
        JsonArray refs=selection.target().getJsonArray("credentialRefs"); if(refs==null||refs.isEmpty()) return Future.failedFuture(GatewayFailure.modelUnavailable());
        JsonObject ref=refs.getJsonObject(0);
        return runtime.credentialResolver().resolve(principal.tenantId(),ref.getLong("providerCredentialId"),ref.getLong("credentialVersion"),ref.getString("authType")).compose(c->{
            MultiMap headers=MultiMap.caseInsensitiveMultiMap(); headers.set("content-type","application/json"); headers.set("accept", request.getHeader("accept") == null ? "application/json" : request.getHeader("accept"));
            if("ANTHROPIC".equals(protocol)){ copy(request,headers,"anthropic-version"); copy(request,headers,"anthropic-beta"); }
            RuntimeCredentialResolver.applyAuthentication(headers,c);
            String url=UpstreamUrlValidator.endpoint(selection.target().getString("baseUrl"),handler.endpoint(),profile);
            return client.post(url,headers,Buffer.buffer(handler.upstreamRequest(inbound,selection.upstreamModelId()).encode()),selection.target().getInteger("connectTimeoutMs",5000),selection.target().getInteger("readTimeoutMs",60000))
                    .compose(x->write(request.response(),x,handler,tenantModel,observation));
        });
    }
    private Future<Void> write(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange, RelayHandler handler,
                               String tenantModel, RelayObservationEvent observation){
        if(exchange.response().statusCode()<200||exchange.response().statusCode()>=300) { exchange.response().resume(); return Future.failedFuture(GatewayFailure.modelUnavailable()); }
        String type=exchange.response().getHeader("content-type");
        if(type!=null&&type.contains("text/event-stream")) return stream(downstream,exchange,handler,tenantModel,type,observation);
        return exchange.response().body().compose(buffer->{ try{
            JsonObject upstream = new JsonObject(buffer);
            runtime.relayEventPublisher().publish(observation.finished(handler.extractUsage(upstream), 200, elapsedMillis(observation)));
            return downstream.setStatusCode(200).putHeader("content-type","application/json").putHeader("x-request-id", observation.requestId())
                    .end(Buffer.buffer(handler.clientResponse(upstream,tenantModel).encode()));
        }catch(RuntimeException e){return Future.failedFuture(GatewayFailure.modelUnavailable());}});
    }
    private Future<Void> stream(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange, RelayHandler handler,
                                String tenantModel,String contentType, RelayObservationEvent observation){
        Promise<Void> completed=Promise.promise(); AtomicBoolean terminal=new AtomicBoolean();
        RelayUsageCollector usageCollector = new RelayUsageCollector(handler);
        SsePayloadRewriter rewriter=new SsePayloadRewriter(handler,tenantModel,usageCollector::accept);
        downstream.setStatusCode(200).putHeader("content-type",contentType).putHeader("x-request-id", observation.requestId());
        downstream.closeHandler(ignored->{ if(terminal.compareAndSet(false,true)){ exchange.request().cancel(); runtime.relayEventPublisher().publish(observation.cancelled(usageCollector.currentUsage(), elapsedMillis(observation))); completed.tryComplete(); }});
        downstream.drainHandler(ignored->{ if(!terminal.get()) exchange.response().resume(); });
        exchange.response().handler(chunk->{ if(terminal.get()) return; Buffer rewritten=rewriter.rewrite(chunk); if(rewritten.length()>0) downstream.write(rewritten); if(downstream.writeQueueFull()) exchange.response().pause(); })
                .endHandler(ignored->{ if(terminal.compareAndSet(false,true)){ Buffer tail=rewriter.finish(); if(tail.length()>0) downstream.write(tail); downstream.end();
                    if(rewriter.hasTerminalEvent()) runtime.relayEventPublisher().publish(observation.finished(usageCollector.currentUsage(), 200, elapsedMillis(observation)));
                    else runtime.relayEventPublisher().publish(observation.failed("UPSTREAM_PROTOCOL", null, usageCollector.currentUsage(), elapsedMillis(observation)));
                    completed.tryComplete(); }})
                .exceptionHandler(error->{ if(terminal.compareAndSet(false,true)){ downstream.write(handler.streamError(STREAM_UPSTREAM_ERROR)); downstream.end(); runtime.relayEventPublisher().publish(observation.failed("UPSTREAM_UNAVAILABLE", null, usageCollector.currentUsage(), elapsedMillis(observation))); completed.tryComplete(); }});
        return completed.future();
    }
    public JsonObject error(String protocol,String message){ return handlerFor(protocol).error(message); }
    private RelayHandler handlerFor(String protocol){ return switch(protocol){case "OPENAI"->openAi;case "ANTHROPIC"->anthropic;default->throw GatewayFailure.unsupported();}; }
    private static void copy(HttpServerRequest r,MultiMap h,String n){String v=r.getHeader(n);if(v!=null)h.set(n,v);}
    private static String apiKey(HttpServerRequest r){String a=r.getHeader("Authorization");return a!=null&&a.regionMatches(true,0,"Bearer ",0,7)?a.substring(7):r.getHeader("x-api-key");}
    private static long elapsedMillis(RelayObservationEvent event) { return Math.max(0L, Duration.between(event.requestStartedAt(), Instant.now()).toMillis()); }
}
