package io.fluxora.platform.upstream.provider.dto;

import java.time.Instant;

/**
 * 上游厂商对外摘要。
 * 不包含 deletedAt；status 由服务层根据 enabled 与删除标记派生（ENABLED / DISABLED）。
 * tenantName 仅供平台管理员跨租户识别归属，私有厂商归属租户可见，共享厂商为 null。
 */
public record ProviderSummary(
        Long id,
        String name,
        String code,
        String scopeType,
        Long tenantId,
        String tenantName,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
