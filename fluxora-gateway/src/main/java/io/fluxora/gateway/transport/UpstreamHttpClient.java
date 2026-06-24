package io.fluxora.gateway.transport;

import io.fluxora.gateway.GatewayFailure;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/** Vert.x 非阻塞上游传输；不包含协议判断、认证选择或业务错误映射。 */
public final class UpstreamHttpClient {
    private final HttpClient client;
    public UpstreamHttpClient(Vertx vertx) { this.client = vertx.createHttpClient(); }
    public Future<Exchange> post(String url, MultiMap headers, Buffer body, int connectTimeoutMs, int readTimeoutMs) {
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(url).setHeaders(headers)
                .setFollowRedirects(false).setConnectTimeout(connectTimeoutMs).setIdleTimeout(readTimeoutMs);
        return client.request(options).compose(request -> {
            request.setFollowRedirects(false).idleTimeout(readTimeoutMs);
            return request.send(body).map(response -> new Exchange(request, response));
        }).recover(error -> Future.failedFuture(GatewayFailure.runtimeUnavailable()));
    }
    public record Exchange(HttpClientRequest request, HttpClientResponse response) {}
}
