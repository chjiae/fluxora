package io.fluxora.gateway.relay.failure;

/** 失败影响范围；RuntimeIncidentMapper 后续据此决定本请求排除和运行时隔离粒度。 */
public enum FailureScope {
    CREDENTIAL,
    PROVIDER_CHANNEL_CREDENTIAL,
    BILLING_ACCOUNT_GROUP,
    QUOTA_SCOPE,
    ROUTE_TARGET,
    PROVIDER_CHANNEL_MODEL,
    PROVIDER_CHANNEL,
    CLIENT,
    UNKNOWN
}
