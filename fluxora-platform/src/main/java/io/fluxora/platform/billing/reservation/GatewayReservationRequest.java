package io.fluxora.platform.billing.reservation;

/**
 * Gateway 专用预冻结请求。金额和价格全部使用十进制字符串，避免 HTTP JSON 浮点误差；
 * 此 DTO 不含 API Key、凭证明文、上游地址、请求正文或消息内容。
 */
public record GatewayReservationRequest(
        String requestId, Long tenantId, Long userId, Long apiKeyId,
        Long tenantModelId, String tenantModelCode, String inboundProtocol, String endpoint,
        String requestStartedAt, String currencyCode, Integer priceVersion,
        String inputPricePerMillion, String outputPricePerMillion,
        String cacheWritePricePerMillion, String cacheReadPricePerMillion,
        Long inputTokenCeiling, Long outputTokenCeiling,
        Long cacheWriteTokenCeiling, Long cacheReadTokenCeiling,
        String reservationAmount) {
}
