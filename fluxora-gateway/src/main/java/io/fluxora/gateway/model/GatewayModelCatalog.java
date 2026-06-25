package io.fluxora.gateway.model;

import io.fluxora.gateway.GatewayFailure;
import io.fluxora.gateway.runtime.RuntimeL1Caches;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenAI 模型发现只读取租户目录快照；不查询 PostgreSQL，不逐一解析路由，也不访问任何上游。
 */
public final class GatewayModelCatalog {
    private final RuntimeL1Caches caches;

    public GatewayModelCatalog(RuntimeL1Caches caches) {
        this.caches = caches;
    }

    public Future<JsonObject> listOpenAiModels(long tenantId) {
        String scopeKey = CatalogScopeKey.of(tenantId, "OPENAI");
        return caches.catalog(scopeKey).map(snapshot -> toOpenAiList(snapshot.payload(), tenantId))
                .recover(error -> Future.failedFuture(error instanceof GatewayFailure
                        ? error : GatewayFailure.runtimeUnavailable()));
    }

    private JsonObject toOpenAiList(JsonObject snapshot, long tenantId) {
        if (snapshot.getLong("tenantId", -1L) != tenantId || !"OPENAI".equals(snapshot.getString("inboundProtocol"))) {
            throw GatewayFailure.runtimeUnavailable();
        }
        JsonArray rawModels = snapshot.getJsonArray("models");
        if (rawModels == null) throw GatewayFailure.runtimeUnavailable();
        List<JsonObject> models = new ArrayList<>();
        for (int index = 0; index < rawModels.size(); index++) {
            JsonObject raw = rawModels.getJsonObject(index);
            if (raw == null || blank(raw.getString("modelCode")) || raw.getLong("created") == null) {
                throw GatewayFailure.runtimeUnavailable();
            }
            models.add(new JsonObject().put("id", raw.getString("modelCode")).put("object", "model")
                    .put("created", raw.getLong("created")).put("owned_by", "fluxora"));
        }
        models.sort(Comparator.comparing(item -> item.getString("id")));
        return new JsonObject().put("object", "list").put("data", new JsonArray(models));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
