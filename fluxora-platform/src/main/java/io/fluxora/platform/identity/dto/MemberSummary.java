package io.fluxora.platform.identity.dto;

import java.time.Instant;

/**
 * 成员列表行 DTO，对外返回的扁平结构。
 *
 * 不包含 passwordHash 与 deletedAt 等敏感/内部字段；status 由后端派生。
 * 用于 GET /api/tenant/{tenantId}/members、GET /api/members 列表响应。
 */
public record MemberSummary(
        Long id,
        String username,
        String displayName,
        String email,
        String roleCode,
        String roleName,
        String status,
        Long tenantId,
        String tenantCode,
        String tenantName,
        Instant createdAt,
        Instant updatedAt
) {}
