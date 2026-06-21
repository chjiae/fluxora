package io.fluxora.platform.tenant;

/**
 * 设置租户过期时间请求 DTO。
 * expireAt 为 null 或空字符串表示清除过期时间（永不过期）。
 */
public record SetTenantExpireRequest(String expireAt) {}
