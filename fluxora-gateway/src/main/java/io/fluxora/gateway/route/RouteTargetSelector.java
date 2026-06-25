package io.fluxora.gateway.route;

import io.fluxora.common.runtime.RouteExecutionEligibility;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 最小 priority 优先，同 priority 组内按正整数 weight 随机加权；不做健康检查、重试或失败切换。 */
public final class RouteTargetSelector {
    public RouteSelection select(JsonArray targets, String inboundProtocol) {
        List<JsonObject> eligible = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            JsonObject target = targets.getJsonObject(index);
            if (target != null && eligible(target, inboundProtocol)) {
                eligible.add(target);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        int priority = eligible.stream().map(target -> target.getInteger("priority", Integer.MAX_VALUE))
                .min(Comparator.naturalOrder()).orElse(Integer.MAX_VALUE);
        List<JsonObject> group = eligible.stream()
                .filter(target -> target.getInteger("priority", Integer.MAX_VALUE) == priority).toList();
        long totalWeight = group.stream().mapToLong(target -> target.getInteger("weight", 0)).sum();
        if (totalWeight <= 0) return null;
        long ticket = ThreadLocalRandom.current().nextLong(totalWeight);
        for (JsonObject target : group) {
            ticket -= target.getInteger("weight", 0);
            if (ticket < 0) return selection(target);
        }
        return selection(group.getLast());
    }

    private boolean eligible(JsonObject target, String inboundProtocol) {
        return RouteExecutionEligibility.targetCallable(
                "ENABLED".equals(target.getString("targetStatus")),
                "ENABLED".equals(target.getString("mappingStatus")),
                "ENABLED".equals(target.getString("candidateStatus")),
                "ENABLED".equals(target.getString("channelStatus")),
                target.getBoolean("hasUsableCredential", false), inboundProtocol, target.getString("outboundProtocol"),
                target.getInteger("priority"), target.getInteger("weight"), target.getLong("routeTargetId"),
                target.getLong("providerChannelId"), target.getLong("providerChannelModelId"));
    }

    private RouteSelection selection(JsonObject target) {
        return new RouteSelection(target.getLong("routeTargetId"), target.getLong("providerChannelId"),
                target.getLong("providerChannelModelId"), target.getString("outboundProtocol"),
                target.getString("upstreamModelId"), target.copy());
    }

}
