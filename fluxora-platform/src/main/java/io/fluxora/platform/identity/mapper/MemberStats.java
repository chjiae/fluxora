package io.fluxora.platform.identity.mapper;

/**
 * 成员聚合统计 DTO，与 TenantStats 同款语义。
 *
 * 字段：
 *   - total：未软删成员总数（限定 scope_type = 'TENANT' 且 tenant_id = 入参）
 *   - enabled：启用中成员数
 *   - disabled：停用中成员数
 *   - tenantAdmins：拥有 TENANT_ADMIN 角色的成员数
 *   - tenantMembers：拥有 TENANT_MEMBER 角色的成员数
 */
public record MemberStats(
        long total,
        long enabled,
        long disabled,
        long tenantAdmins,
        long tenantMembers
) {}
