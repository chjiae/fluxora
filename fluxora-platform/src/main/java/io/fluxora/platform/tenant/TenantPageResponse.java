package io.fluxora.platform.tenant;

import java.util.List;

/**
 * 租户分页响应 DTO。
 * 替代 {@code ApiResponse<Map<String, Object>>}，提供类型安全的列表接口响应。
 */
public record TenantPageResponse(List<Tenant> items, long total, int page, int size) {}
