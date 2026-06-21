package io.fluxora.platform.identity.dto;

/**
 * 编辑成员基础资料请求体：仅 displayName 与 email。
 * 用户名一旦创建不可修改，避免 JWT 与登录态混乱；角色调整使用单独接口 /role。
 */
public record UpdateProfileRequest(String displayName, String email) {}
