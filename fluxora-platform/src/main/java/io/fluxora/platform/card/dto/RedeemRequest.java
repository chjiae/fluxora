package io.fluxora.platform.card.dto;

/** 卡密核销请求体；code 为用户输入的原始字符串，service 内规范化 */
public record RedeemRequest(String code) {}