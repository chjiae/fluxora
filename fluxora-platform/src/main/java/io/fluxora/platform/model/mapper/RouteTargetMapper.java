package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.RouteTarget;
import io.fluxora.platform.model.dto.RouteTargetSummary;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 路由目标数据访问契约。
 * 写入前服务层强制四方一致：modelRoute.tenant_id == mapping.tenant_id == candidate.tenant_id == channel.tenant_id；
 * 服务层还强制协议兼容（model_route.inbound_protocol == provider_base_url.protocol）。
 */
@Mapper
public interface RouteTargetMapper {

    void insert(RouteTarget entity);

    Optional<RouteTarget> findById(@Param("id") Long id);

    /** 列表投影：联表 mapping/candidate/channel 一次取回，避免前端 N+1；不返回 deleted_at。 */
    List<RouteTargetSummary> findByRoute(@Param("modelRouteId") Long modelRouteId);

    /** 同路由同映射未删除唯一性检查。 */
    int existsActivePair(@Param("modelRouteId") Long modelRouteId,
                         @Param("tenantModelCandidateMappingId") Long mappingId);

    void update(RouteTarget entity);

    void setEnabled(@Param("id") Long id,
                    @Param("enabled") boolean enabled,
                    @Param("updatedBy") Long updatedBy);

    void softDelete(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    /**
     * 当前映射是否仍被「未删除 + ENABLED」RouteTarget 引用；
     * 用于阻止删除被启用路由占用的映射。
     */
    long countActiveByMapping(@Param("mappingId") Long mappingId);

    /**
     * 解析候选映射对应通道的协议（OPENAI / ANTHROPIC），用于服务层在写入 RouteTarget 时
     * 校验「Route.inbound_protocol == 通道协议」。
     * 仅返回当前未删除、可用资源的协议；任一资源已删除 → 返回空 Optional 由调用方拒绝。
     */
    Optional<MappingResolution> resolveMapping(@Param("mappingId") Long mappingId);

    /**
     * 路由目标写入所需的事实快照：候选 tenant_id、所属通道 ID、协议、上游标识。
     * 用于服务层一次性获得四方校验材料，避免逐条 SQL（N+1）。
     */
    record MappingResolution(
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
}
