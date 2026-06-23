package io.fluxora.platform.model.mapper;

/**
 * 路由目标写入所需的事实快照：候选 tenant_id、所属通道 ID、协议、上游标识。
 * 用于服务层一次性获得四方校验材料，避免逐条 SQL（N+1）。
 */
public record MappingResolution(
        Long mappingId,
        Long mappingTenantId,
        Long providerChannelModelId,
        Long providerChannelId,
        Long channelTenantId,
        String protocol,
        String upstreamModelId,
        boolean mappingEnabled,
        boolean candidateEnabled,
        boolean channelEnabled
) {}