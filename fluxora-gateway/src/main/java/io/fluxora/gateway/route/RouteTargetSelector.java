package io.fluxora.gateway.route;

import io.fluxora.common.runtime.RouteExecutionEligibility;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
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
        // 选取最高可用优先级（数值越小优先级越高），仅在该优先级组内进行加权随机
        int priority = Integer.MAX_VALUE;
        for (JsonObject target : eligible) {
            priority = Math.min(priority, target.getInteger("priority", Integer.MAX_VALUE));
        }
        // 收集当前最高优先级组内的目标，低优先级目标仅在当前组无可用目标时由外层空判定处理
        List<JsonObject> group = new ArrayList<>();
        for (JsonObject target : eligible) {
            if (target.getInteger("priority", Integer.MAX_VALUE) == priority) {
                group.add(target);
            }
        }
        // 累加该组内全部目标的权重，作为加权随机的总区间
        long totalWeight = 0L;
        for (JsonObject target : group) {
            totalWeight += target.getInteger("weight", 0);
        }
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
