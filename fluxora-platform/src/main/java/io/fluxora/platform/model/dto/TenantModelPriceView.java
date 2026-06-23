package io.fluxora.platform.model.dto;

import java.time.Instant;

/**
 * 租户模型价格视图。
 * 金额字段以字符串呈现（已通过 PreciseNumberJsonConfiguration 全局序列化 BigDecimal 为字符串），
 * 接口契约绝不使用 IEEE-754 Number。
 * 价格版本不可篡改；新增版本而非覆盖；同一模型同一时刻最多一个 expired_at IS NULL 的当前版本。
 */
public record TenantModelPriceView(
        Long id,
        Long tenantId,
        Long tenantModelId,
        String currencyCode,
        /** 输入单价：每 100 万 Token 的 CNY 8 位小数 */
        String inputPricePerMillion,
        /** 输出单价：每 100 万 Token 的 CNY 8 位小数 */
        String outputPricePerMillion,
        /** 缓存写入单价；不支持缓存的模型为 null */
        String cacheWritePricePerMillion,
        /** 缓存读取单价；不支持缓存的模型为 null */
        String cacheReadPricePerMillion,
        int version,
        Instant effectiveAt,
        /** 当前有效版本为 null；历史版本携带失效时刻 */
        Instant expiredAt,
        Instant createdAt
) {
}
