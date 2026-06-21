package io.fluxora.platform.identity.dto;

/**
 * 重置成员密码请求体。
 * 新密码立即生效、原密码立即失效；MemberService 内部完成强度校验与 BCrypt 加密。
 * 不允许在响应、日志、前端状态中保留明文密码。
 */
public record ResetPasswordRequest(String newPassword) {}
