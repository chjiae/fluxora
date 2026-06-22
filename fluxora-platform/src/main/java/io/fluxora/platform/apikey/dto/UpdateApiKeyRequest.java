package io.fluxora.platform.apikey.dto;

/**
 * 编辑 API Key 请求体。
 *
 * 允许修改：name、expireAt。
 * 不允许修改：key_prefix、key_hash、user_id、tenant_id 等原始/归属字段。
 * expireAtAction："SET"（写入 expireAt 值）/ "CLEAR"（清空，表示永不过期）。
 */
public record UpdateApiKeyRequest(
        String name,
        String expireAtAction,
        String expireAt
) {}
