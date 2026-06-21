package io.fluxora.platform.identity.dto;

/**
 * 调整成员角色请求体。
 *
 * 角色合法性与可分配性由 MemberService.RoleAssignability 校验：
 *   - 不可分配 PLATFORM_ADMIN；
 *   - 租户管理员仅可分配 TENANT_MEMBER；
 *   - 不可降级最后一名有效 TENANT_ADMIN。
 */
public record UpdateRoleRequest(String roleCode) {}
