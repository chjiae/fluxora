package io.fluxora.gateway.auth;

/** Gateway 鉴权成功后的最小主体；不携带 API Key 原文、邮箱、余额或其他用户隐私。 */
public record AuthenticatedPrincipal(long apiKeyId, long tenantId, long userId) {
}
