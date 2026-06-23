package io.fluxora.platform.model;

import java.time.Instant;

/** 上游模型候选安全投影：不返回来源凭证明文、密文或同步原始响应。 */
public class ProviderChannelModelSummary {
 private Long id,tenantId,providerChannelId,platformModelId; private String upstreamModelId,displayName,sourceType,status,lastSyncSummary; private Instant lastSyncedAt,createdAt,updatedAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getTenantId(){return tenantId;} public void setTenantId(Long v){tenantId=v;} public Long getProviderChannelId(){return providerChannelId;} public void setProviderChannelId(Long v){providerChannelId=v;} public Long getPlatformModelId(){return platformModelId;} public void setPlatformModelId(Long v){platformModelId=v;} public String getUpstreamModelId(){return upstreamModelId;} public void setUpstreamModelId(String v){upstreamModelId=v;} public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;} public String getSourceType(){return sourceType;} public void setSourceType(String v){sourceType=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getLastSyncSummary(){return lastSyncSummary;} public void setLastSyncSummary(String v){lastSyncSummary=v;} public Instant getLastSyncedAt(){return lastSyncedAt;} public void setLastSyncedAt(Instant v){lastSyncedAt=v;} public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;} public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}
