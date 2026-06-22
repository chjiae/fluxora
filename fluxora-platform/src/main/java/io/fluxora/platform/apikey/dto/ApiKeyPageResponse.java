package io.fluxora.platform.apikey.dto;

import java.util.List;

/** API Key 分页响应；字段名、形状与 MemberPageResponse 一致 */
public record ApiKeyPageResponse(
        List<ApiKeySummary> items,
        long total,
        int page,
        int size
) {}
