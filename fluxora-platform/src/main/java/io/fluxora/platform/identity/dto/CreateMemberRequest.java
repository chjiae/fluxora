package io.fluxora.platform.identity.dto;

/**
 * 创建成员请求体。
 *
 * 密码以明文形式接收，由 {@link io.fluxora.platform.identity.MemberService} 内部
 * 完成强度校验与 BCrypt 加密；切勿在 Controller 或前端日志中保留 password 原文。
 */
public record CreateMemberRequest(
        String username,
        String displayName,
        String email,
        String password,
        String roleCode
) {}
