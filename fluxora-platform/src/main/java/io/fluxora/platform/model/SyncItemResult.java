package io.fluxora.platform.model;

/** 单个模型同步结果；只含模型标识和安全原因，不包含上游原始响应或凭证信息。 */
public record SyncItemResult(String upstreamModelId, String result, String reason) {}
