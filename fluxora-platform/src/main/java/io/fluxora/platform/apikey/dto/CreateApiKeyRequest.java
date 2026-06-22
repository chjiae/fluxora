package io.fluxora.platform.apikey.dto;

/**
 * 创建 API Key 请求体。
 *
 * 字段：
 *   name      —— Key 显示名称；服务端做长度/字符校验
 *   forUserId —— 仅租户/平台管理员路径接收；普通用户路径忽略（强制 = 当前用户）
 *   expireAt  —— ISO-8601 字符串；null 表示永不过期
 */
public record CreateApiKeyRequest(
        String name,
        Long forUserId,
        String expireAt
) {}
