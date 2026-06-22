package io.fluxora.platform.upstream.channel.mapper;

import java.time.Instant;

/**
 * 通道列表关联投影。
 * 一次 JOIN 取出租户名称、厂商名称、协议、规范化地址与凭证数量，避免逐行加载关联数据（N+1）。
 * 不选择 deleted_at；getStatus 依据 enabled 派生（查询已过滤删除行）。
 */
public class ProviderChannelRow {
    private Long id;
    private Long tenantId;
    private String tenantName;
    private Long providerId;
    private String providerName;
    private Long providerBaseUrlId;
    private String protocol;
    private String normalizedBaseUrl;
    private String name;
    private boolean enabled;
    private int priority;
    private int weight;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private String remark;
    private long credentialCount;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public Long getProviderId() { return providerId; }
    public void setProviderId(Long providerId) { this.providerId = providerId; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public Long getProviderBaseUrlId() { return providerBaseUrlId; }
    public void setProviderBaseUrlId(Long providerBaseUrlId) { this.providerBaseUrlId = providerBaseUrlId; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getNormalizedBaseUrl() { return normalizedBaseUrl; }
    public void setNormalizedBaseUrl(String normalizedBaseUrl) { this.normalizedBaseUrl = normalizedBaseUrl; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getCredentialCount() { return credentialCount; }
    public void setCredentialCount(long credentialCount) { this.credentialCount = credentialCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getStatus() { return enabled ? "ENABLED" : "DISABLED"; }
}
