package io.fluxora.platform.identity.dto;

/**
 * 角色选项，用于前端「可分配角色」下拉。
 * 服务层按当前操作者权限过滤后返回，前端直接渲染，不再做角色可见性判断。
 */
public record RoleOption(String code, String name) {}
