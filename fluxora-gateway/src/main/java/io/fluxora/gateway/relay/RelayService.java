package io.fluxora.gateway.relay;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.GatewayRuntime;
import io.fluxora.gateway.auth.AuthenticatedPrincipal;
import io.fluxora.gateway.credential.RuntimeCredentialResolver;
import io.fluxora.gateway.observability.RelayObservationEvent;
import io.fluxora.gateway.observability.RelayPriceSnapshot;
import io.fluxora.gateway.observability.RelayRequestId;
import io.fluxora.gateway.observability.UpstreamDispatchState;
import io.fluxora.gateway.relay.failure.UpstreamSignal;
import io.fluxora.gateway.relay.orchestration.AttemptFailure;
import io.fluxora.gateway.relay.orchestration.AttemptStateMachine;
import io.fluxora.gateway.relay.orchestration.RelayAttemptContext;
import io.fluxora.gateway.relay.scheduling.DispatchExclusions;
import io.fluxora.gateway.relay.scheduling.DispatchPlan;
import io.fluxora.gateway.transport.UpstreamHttpClient;
import io.fluxora.gateway.transport.UpstreamUrlValidator;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway 中继主编排。
 *
 * <p>该服务负责把单个客户端请求拆成清晰阶段：解析、鉴权与余额准入、路由快照读取、
 * 首次调度、Attempt 执行和终态事件投递。它不访问 PostgreSQL，不同步调用 Platform 查询或扣减余额，
 * 不解析失败策略细节，也不直接修改运行时快照。</p>
 */
public final class RelayService {
    private static final Logger log = LoggerFactory.getLogger(RelayService.class);
    private static final String STREAM_UPSTREAM_ERROR = "当前模型暂不可用，请稍后重试";

    private final GatewayRuntime runtime;
    private final UpstreamHttpClient client;
    private final String profile;
    private final RelayHandler openAi = new OpenAiRelayHandler();
    private final RelayHandler anthropic = new AnthropicRelayHandler();

    public RelayService(GatewayRuntime runtime, UpstreamHttpClient client, String profile) {
        this.runtime = runtime;
        this.client = client;
        this.profile = profile;
    }

    /**
     * 执行一次中继请求。余额准入已经内聚在 GatewayAuthenticator 的 AUTH_USER 快照判定中；
     * 后续阶段不得再同步查询余额或引入终态前金额写入。
     */
    public Future<Void> relay(HttpServerRequest request, String inboundProtocol, Buffer body) {
        RelayInbound inbound = parseInbound(request, inboundProtocol, body);

        Future<AuthenticatedPrincipal> authenticatedFuture = runtime.authenticator().authenticate(apiKey(request));

        Future<RelayFlow> routedFuture = authenticatedFuture
                .compose(principal -> resolveRoute(principal, inbound));

        Future<RelayFlow> plannedFuture = routedFuture
                .compose(this::acquireInitialPlan);

        return plannedFuture
                .compose(flow -> executeAttemptLoop(request, flow));
    }

    private RelayInbound parseInbound(HttpServerRequest request, String inboundProtocol, Buffer body) {
        JsonObject payload;
        try {
            payload = new JsonObject(body);
        } catch (RuntimeException error) {
            throw GatewayFailure.invalidRequest();
        }
        String model = payload.getString("model");
        if (model == null || model.isBlank()) {
            throw GatewayFailure.invalidRequest();
        }
        return new RelayInbound(inboundProtocol, request.path(), payload, model, payload.getBoolean("stream", false));
    }

    private Future<RelayFlow> resolveRoute(AuthenticatedPrincipal principal, RelayInbound inbound) {
        return runtime.routeResolver()
                .resolveRouteSnapshot(principal.tenantId(), inbound.protocol(), inbound.tenantModelCode(), inbound.streaming())
                .map(routeSnapshot -> {
                    JsonObject normalizedInbound = RelayRequestNormalizer.normalize(inbound.payload(), routeSnapshot);
                    return new RelayFlow(principal, inbound, routeSnapshot, normalizedInbound, null, null);
                });
    }

    private Future<RelayFlow> acquireInitialPlan(RelayFlow flow) {
        String requestId = RelayRequestId.next();
        return runtime.dispatchPlanner()
                .planAndAcquire(flow.routeSnapshot(), DispatchExclusions.none(), requestId, requestId + "-attempt-1")
                .map(plan -> flow.withInitialPlan(requestId, plan));
    }

    private Future<Void> executeAttemptLoop(HttpServerRequest request, RelayFlow flow) {
        RelayObservationEvent started = startedEvent(request, flow);
        runtime.relayEventPublisher().publish(started);
        log.debug("Gateway 中继请求已准入：requestId={}, tenantId={}, tenantModelCode={}, inboundProtocol={}, snapshotVersion={}",
                flow.requestId(), flow.principal().tenantId(), flow.inbound().tenantModelCode(),
                flow.inbound().protocol(), flow.routeSnapshot().getLong("runtimeVersion", -1L));

        RelayAttemptContext context = new RelayAttemptContext(flow.requestId(), flow.principal().tenantId(),
                flow.routeSnapshot(), flow.initialPlan(), 3, 5_000);
        DispatchTracker dispatch = new DispatchTracker();
        return runtime.attemptCoordinator().execute(context,
                (attemptPlan, stateMachine) -> forwardAttempt(request, flow, attemptPlan, started, dispatch, stateMachine))
                .recover(error -> {
                    runtime.relayEventPublisher().publish(started.failed("UPSTREAM_UNAVAILABLE", null,
                            null, elapsedMillis(started), dispatch.current()));
                    return Future.failedFuture(error);
                });
    }

    private RelayObservationEvent startedEvent(HttpServerRequest request, RelayFlow flow) {
        DispatchPlan plan = flow.initialPlan();
        return RelayObservationEvent.started(flow.requestId(), flow.principal(), flow.inbound().protocol(),
                request.path(), flow.inbound().streaming(), plan.routeTargetId(), plan.providerChannelId(),
                plan.providerChannelModelId(), RelayPriceSnapshot.fromRoute(flow.routeSnapshot()));
    }

    /** 上游 Attempt：选择协议处理器、解析运行时凭证、派发请求并处理响应。 */
    private Future<Void> forwardAttempt(HttpServerRequest request, RelayFlow flow, DispatchPlan plan,
                                        RelayObservationEvent observation, DispatchTracker dispatch,
                                        AttemptStateMachine stateMachine) {
        if (!flow.inbound().protocol().equals(plan.routeSelection().outboundProtocol())) {
            return Future.failedFuture(GatewayFailure.modelUnavailable());
        }
        RelayHandler handler = handlerFor(flow.inbound().protocol());

        Future<ResolvedAttempt> resolvedFuture = resolveCredential(request, flow, plan, handler);

        Future<UpstreamHttpClient.Exchange> exchangeFuture = resolvedFuture
                .compose(resolved -> invokeUpstream(resolved, dispatch, stateMachine));

        return exchangeFuture
                .recover(error -> transportFailure(plan, flow.inbound().protocol(), error, dispatch, stateMachine))
                .compose(exchange -> handleExchange(request.response(), exchange, handler, flow, observation,
                        dispatch, plan, stateMachine));
    }

    private Future<ResolvedAttempt> resolveCredential(HttpServerRequest request, RelayFlow flow,
                                                      DispatchPlan plan, RelayHandler handler) {
        JsonObject credentialRef = plan.credentialRef();
        return runtime.credentialResolver()
                .resolve(flow.principal().tenantId(), credentialRef.getLong("providerCredentialId"),
                        credentialRef.getLong("credentialVersion"), credentialRef.getString("authType"))
                .map(credential -> {
                    MultiMap headers = upstreamHeaders(request, flow.inbound().protocol(), credential);
                    String url = UpstreamUrlValidator.endpoint(plan.routeSelection().target().getString("baseUrl"),
                            handler.endpoint(), profile);
                    Buffer payload = Buffer.buffer(handler.upstreamRequest(
                            flow.normalizedInbound(), plan.routeSelection().upstreamModelId()).encode());
                    return new ResolvedAttempt(plan, headers, url, payload);
                });
    }

    private Future<UpstreamHttpClient.Exchange> invokeUpstream(ResolvedAttempt resolved, DispatchTracker dispatch,
                                                               AttemptStateMachine stateMachine) {
        JsonObject target = resolved.plan().routeSelection().target();
        dispatch.markDispatched();
        log.debug("上游请求开始：routeTargetId={}, providerChannelId={}, credentialId={}, priorityTier={}",
                resolved.plan().routeTargetId(), resolved.plan().providerChannelId(),
                resolved.plan().providerCredentialId(), resolved.plan().priorityTier());
        return client.post(resolved.url(), resolved.headers(), resolved.payload(),
                target.getInteger("connectTimeoutMs", 5_000),
                target.getInteger("readTimeoutMs", 60_000), stateMachine::markRequestFullySent);
    }

    private Future<UpstreamHttpClient.Exchange> transportFailure(DispatchPlan plan, String protocol, Throwable error,
                                                                 DispatchTracker dispatch,
                                                                 AttemptStateMachine stateMachine) {
        dispatch.markUnknown();
        return Future.failedFuture(attemptFailure(plan, protocol, error, stateMachine));
    }

    private Future<Void> handleExchange(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange,
                                        RelayHandler handler, RelayFlow flow, RelayObservationEvent observation,
                                        DispatchTracker dispatch, DispatchPlan plan,
                                        AttemptStateMachine stateMachine) {
        dispatch.markResponseStarted();
        int statusCode = exchange.response().statusCode();
        log.debug("上游响应状态：requestId={}, routeTargetId={}, status={}",
                observation.requestId(), plan.routeTargetId(), statusCode);
        if (statusCode < 200 || statusCode >= 300) {
            return classifyHttpFailure(exchange, flow.inbound().protocol(), plan, stateMachine);
        }
        String contentType = exchange.response().getHeader("content-type");
        if (contentType != null && contentType.contains("text/event-stream")) {
            return stream(downstream, exchange, handler, flow.inbound().tenantModelCode(), contentType,
                    observation, dispatch, stateMachine);
        }
        return writeJsonResponse(downstream, exchange, handler, flow.inbound().tenantModelCode(), observation,
                dispatch, stateMachine);
    }

    private Future<Void> classifyHttpFailure(UpstreamHttpClient.Exchange exchange, String protocol,
                                             DispatchPlan plan, AttemptStateMachine stateMachine) {
        String contentType = exchange.response().getHeader("content-type");
        Long retryAfter = parseRetryAfter(exchange.response().getHeader("retry-after"));
        return exchange.response().body()
                .compose(buffer -> {
                    JsonObject safeBody = safeJson(buffer).mergeIn(resourceFields(plan));
                    UpstreamSignal signal = UpstreamSignal.http(protocol, exchange.response().statusCode(),
                            contentType, safeBody, retryAfter);
                    return Future.failedFuture(new AttemptFailure(signal, stateMachine.snapshot(), plan));
                });
    }

    private Future<Void> writeJsonResponse(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange,
                                           RelayHandler handler, String tenantModel,
                                           RelayObservationEvent observation, DispatchTracker dispatch,
                                           AttemptStateMachine stateMachine) {
        return exchange.response().body()
                .compose(buffer -> {
                    try {
                        JsonObject upstream = new JsonObject(buffer);
                        stateMachine.markExecutionMarkerReceived();
                        stateMachine.markClientCommitted();
                        runtime.relayEventPublisher().publish(observation.finished(handler.extractUsage(upstream),
                                200, elapsedMillis(observation), dispatch.current()));
                        return downstream.setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .putHeader("x-request-id", observation.requestId())
                                .end(Buffer.buffer(handler.clientResponse(upstream, tenantModel).encode()));
                    } catch (RuntimeException error) {
                        return Future.failedFuture(GatewayFailure.modelUnavailable());
                    }
                });
    }

    private Future<Void> stream(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange, RelayHandler handler,
                                String tenantModel, String contentType, RelayObservationEvent observation,
                                DispatchTracker dispatch, AttemptStateMachine stateMachine) {
        Promise<Void> completed = Promise.promise();
        AtomicBoolean terminal = new AtomicBoolean();
        RelayUsageCollector usageCollector = new RelayUsageCollector(handler);
        SsePayloadRewriter rewriter = new SsePayloadRewriter(handler, tenantModel, usageCollector::accept);

        stateMachine.markExecutionMarkerReceived();
        stateMachine.markClientCommitted();
        downstream.setStatusCode(200).putHeader("content-type", contentType)
                .putHeader("x-request-id", observation.requestId());
        downstream.closeHandler(ignored -> handleClientClose(exchange, observation, dispatch, usageCollector,
                completed, terminal));
        downstream.drainHandler(ignored -> {
            if (!terminal.get()) {
                exchange.response().resume();
            }
        });
        exchange.response()
                .handler(chunk -> handleStreamChunk(downstream, exchange, rewriter, terminal, chunk))
                .endHandler(ignored -> handleStreamEnd(downstream, observation, dispatch, usageCollector,
                        rewriter, completed, terminal))
                .exceptionHandler(error -> handleStreamFailure(downstream, handler, observation, dispatch,
                        usageCollector, completed, terminal));
        return completed.future();
    }

    private void handleClientClose(UpstreamHttpClient.Exchange exchange, RelayObservationEvent observation,
                                   DispatchTracker dispatch, RelayUsageCollector usageCollector,
                                   Promise<Void> completed, AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        exchange.request().cancel();
        runtime.relayEventPublisher().publish(observation.cancelled(usageCollector.currentUsage(),
                elapsedMillis(observation), dispatch.current()));
        log.warn("客户端已断开中继请求：requestId={}, dispatchState={}", observation.requestId(), dispatch.current());
        completed.tryComplete();
    }

    private void handleStreamChunk(HttpServerResponse downstream, UpstreamHttpClient.Exchange exchange,
                                   SsePayloadRewriter rewriter, AtomicBoolean terminal, Buffer chunk) {
        if (terminal.get()) {
            return;
        }
        Buffer rewritten = rewriter.rewrite(chunk);
        if (rewritten.length() > 0) {
            downstream.write(rewritten);
        }
        if (downstream.writeQueueFull()) {
            exchange.response().pause();
        }
    }

    private void handleStreamEnd(HttpServerResponse downstream, RelayObservationEvent observation,
                                 DispatchTracker dispatch, RelayUsageCollector usageCollector,
                                 SsePayloadRewriter rewriter, Promise<Void> completed, AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        Buffer tail = rewriter.finish();
        if (tail.length() > 0) {
            downstream.write(tail);
        }
        downstream.end();
        if (rewriter.hasTerminalEvent()) {
            runtime.relayEventPublisher().publish(observation.finished(usageCollector.currentUsage(),
                    200, elapsedMillis(observation), dispatch.current()));
        } else {
            runtime.relayEventPublisher().publish(observation.failed("UPSTREAM_PROTOCOL", null,
                    usageCollector.currentUsage(), elapsedMillis(observation), dispatch.current()));
        }
        completed.tryComplete();
    }

    private void handleStreamFailure(HttpServerResponse downstream, RelayHandler handler,
                                     RelayObservationEvent observation, DispatchTracker dispatch,
                                     RelayUsageCollector usageCollector, Promise<Void> completed,
                                     AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        downstream.write(handler.streamError(STREAM_UPSTREAM_ERROR));
        downstream.end();
        runtime.relayEventPublisher().publish(observation.failed("UPSTREAM_UNAVAILABLE", null,
                usageCollector.currentUsage(), elapsedMillis(observation), dispatch.current()));
        log.warn("上游流式响应中断：requestId={}, dispatchState={}", observation.requestId(), dispatch.current());
        completed.tryComplete();
    }

    public JsonObject error(String protocol, String message) {
        return handlerFor(protocol).error(message);
    }

    private RelayHandler handlerFor(String protocol) {
        return switch (protocol) {
            case "OPENAI" -> openAi;
            case "ANTHROPIC" -> anthropic;
            default -> throw GatewayFailure.unsupported();
        };
    }

    private static MultiMap upstreamHeaders(HttpServerRequest request, String protocol,
                                            RuntimeCredentialResolver.ResolvedCredential credential) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("content-type", "application/json");
        headers.set("accept", request.getHeader("accept") == null ? "application/json" : request.getHeader("accept"));
        if ("ANTHROPIC".equals(protocol)) {
            copy(request, headers, "anthropic-version");
            copy(request, headers, "anthropic-beta");
        }
        RuntimeCredentialResolver.applyAuthentication(headers, credential);
        return headers;
    }

    private static void copy(HttpServerRequest request, MultiMap headers, String name) {
        String value = request.getHeader(name);
        if (value != null) {
            headers.set(name, value);
        }
    }

    private static String apiKey(HttpServerRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7) : request.getHeader("x-api-key");
    }

    private static long elapsedMillis(RelayObservationEvent event) {
        return Math.max(0L, Duration.between(event.requestStartedAt(), Instant.now()).toMillis());
    }

    private static AttemptFailure attemptFailure(DispatchPlan plan, String protocol, Throwable error,
                                                 AttemptStateMachine stateMachine) {
        return new AttemptFailure(UpstreamSignal.transport(error, stateMachine.snapshot().requestWriteState(),
                resourceFields(plan)), stateMachine.snapshot(), plan);
    }

    /** 分类器只需要稳定结构字段；上游 message、请求正文、响应片段和 request id 都不能进入日志或事件。 */
    private static JsonObject safeJson(Buffer buffer) {
        if (buffer == null || buffer.length() == 0 || buffer.length() > 16_384) {
            return new JsonObject();
        }
        try {
            JsonObject raw = new JsonObject(buffer.toString(StandardCharsets.UTF_8));
            JsonObject sanitized = new JsonObject();
            copyStable(raw, sanitized, "type");
            copyStable(raw, sanitized, "code");
            JsonObject error = raw.getJsonObject("error");
            if (error != null) {
                JsonObject safeError = new JsonObject();
                copyStable(error, safeError, "type");
                copyStable(error, safeError, "code");
                if (safeError.size() > 0) {
                    sanitized.put("error", safeError);
                }
            }
            return sanitized;
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static void copyStable(JsonObject source, JsonObject target, String field) {
        String value = source.getString(field);
        if (value != null && value.length() <= 128) {
            target.put(field, value);
        }
    }

    private static Long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Math.max(0L, Long.parseLong(header.trim())) * 1_000L;
        } catch (NumberFormatException ignored) {
            try {
                long millis = Duration.between(Instant.now(),
                        ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                return Math.max(0L, millis);
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private static JsonObject resourceFields(DispatchPlan plan) {
        return new JsonObject()
                .put("routeTargetId", plan.routeTargetId())
                .put("providerChannelId", plan.providerChannelId())
                .put("providerChannelModelId", plan.providerChannelModelId())
                .put("providerChannelCredentialId", plan.providerChannelCredentialId())
                .put("providerCredentialId", plan.providerCredentialId())
                .put("quotaScope", plan.quotaScope())
                .put("billingAccountGroup", plan.billingAccountGroup());
    }

    private record RelayInbound(String protocol, String endpoint, JsonObject payload,
                                String tenantModelCode, boolean streaming) {
    }

    private record RelayFlow(AuthenticatedPrincipal principal, RelayInbound inbound, JsonObject routeSnapshot,
                             JsonObject normalizedInbound, String requestId, DispatchPlan initialPlan) {
        private RelayFlow withInitialPlan(String newRequestId, DispatchPlan plan) {
            return new RelayFlow(principal, inbound, routeSnapshot, normalizedInbound, newRequestId, plan);
        }
    }

    private record ResolvedAttempt(DispatchPlan plan, MultiMap headers, String url, Buffer payload) {
    }

    /** 仅记录安全派发阶段；状态 UNKNOWN 会让 Platform 保留人工确认入口。 */
    private static final class DispatchTracker {
        private UpstreamDispatchState state = UpstreamDispatchState.NOT_DISPATCHED;

        void markDispatched() { state = UpstreamDispatchState.DISPATCHED; }
        void markResponseStarted() { state = UpstreamDispatchState.RESPONSE_STARTED; }
        void markUnknown() { state = UpstreamDispatchState.UNKNOWN; }
        UpstreamDispatchState current() { return state; }
    }
}
