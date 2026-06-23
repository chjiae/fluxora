package io.fluxora.platform.model;
/** 模型同步内部投影；不对外返回凭证或加密信息。 */
public record ChannelDiscoveryInfo(Long tenantId,String protocol,String normalizedBaseUrl) {}
