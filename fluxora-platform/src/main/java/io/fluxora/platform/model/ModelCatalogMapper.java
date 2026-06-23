package io.fluxora.platform.model;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 模型目录数据访问契约；全部 SQL 位于 ModelCatalogMapper.xml。 */
@Mapper
public interface ModelCatalogMapper {
    List<PlatformModelSummary> findPlatformModels(@Param("keyword") String keyword, @Param("enabled") Boolean enabled, @Param("offset") int offset, @Param("limit") int limit);
    long countPlatformModels(@Param("keyword") String keyword, @Param("enabled") Boolean enabled);
    Optional<PlatformModelSummary> findPlatformModel(@Param("id") Long id);
    boolean existsPlatformCode(@Param("code") String code);
    void insertPlatformModel(@Param("model") PlatformModel model);
    void updatePlatformModel(@Param("model") PlatformModel model);
    void setPlatformModelEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDeletePlatformModel(@Param("id") Long id);
    List<ProviderChannelModelSummary> findChannelModels(@Param("channelId") Long channelId,@Param("tenantId") Long tenantId,@Param("keyword") String keyword,@Param("enabled") Boolean enabled,@Param("offset") int offset,@Param("limit") int limit);
    long countChannelModels(@Param("channelId") Long channelId,@Param("tenantId") Long tenantId,@Param("keyword") String keyword,@Param("enabled") Boolean enabled);
    Optional<ProviderChannelModelSummary> findChannelModel(@Param("id") Long id);
    boolean existsChannelModel(@Param("channelId") Long channelId,@Param("upstreamModelId") String upstreamModelId);
    void insertChannelModel(@Param("tenantId") Long tenantId,@Param("channelId") Long channelId,@Param("upstreamModelId") String upstreamModelId,@Param("displayName") String displayName,@Param("userId") Long userId);
    void updateChannelModel(@Param("id") Long id,@Param("displayName") String displayName,@Param("enabled") boolean enabled,@Param("userId") Long userId);
    void softDeleteChannelModel(@Param("id") Long id,@Param("userId") Long userId);
    void mapChannelModel(@Param("id") Long id,@Param("platformModelId") Long platformModelId,@Param("userId") Long userId);
    List<TenantModelSummary> findTenantModels(@Param("tenantId") Long tenantId,@Param("offset") int offset,@Param("limit") int limit);
    long countTenantModels(@Param("tenantId") Long tenantId);
    Optional<TenantModelSummary> findTenantModel(@Param("id") Long id);
    boolean existsTenantModel(@Param("tenantId") Long tenantId,@Param("platformModelId") Long platformModelId);
    void insertTenantModel(@Param("tenantId") Long tenantId,@Param("platformModelId") Long platformModelId,@Param("displayName") String displayName,@Param("description") String description,@Param("userId") Long userId);
    Optional<PriceView> currentPlatformPrice(@Param("platformModelId") Long platformModelId);
    List<PriceView> platformPriceHistory(@Param("platformModelId") Long platformModelId);
    void expirePlatformPrice(@Param("platformModelId") Long platformModelId);
    void insertPlatformPrice(@Param("platformModelId") Long platformModelId,@Param("input") java.math.BigDecimal input,@Param("output") java.math.BigDecimal output,@Param("cacheWrite") java.math.BigDecimal cacheWrite,@Param("cacheRead") java.math.BigDecimal cacheRead,@Param("userId") Long userId);
    Optional<PriceView> currentTenantPrice(@Param("tenantModelId") Long tenantModelId);
    void expireTenantPrice(@Param("tenantModelId") Long tenantModelId);
    void insertTenantPrice(@Param("tenantModelId") Long tenantModelId,@Param("input") java.math.BigDecimal input,@Param("output") java.math.BigDecimal output,@Param("cacheWrite") java.math.BigDecimal cacheWrite,@Param("cacheRead") java.math.BigDecimal cacheRead,@Param("userId") Long userId);
    List<PriceView> tenantPriceHistory(@Param("tenantModelId") Long tenantModelId);
    void setTenantPriceMode(@Param("tenantModelId") Long tenantModelId,@Param("mode") String mode,@Param("userId") Long userId);
    boolean tenantModelHasRoute(@Param("tenantModelId") Long tenantModelId);
    boolean tenantModelCanEnable(@Param("tenantModelId") Long tenantModelId);
    void setTenantModelStatus(@Param("tenantModelId") Long tenantModelId,@Param("status") String status,@Param("userId") Long userId);
    List<ModelRouteSummary> findRoutes(@Param("tenantModelId") Long tenantModelId);
    Optional<ModelRouteSummary> findRoute(@Param("id") Long id);
    boolean existsRouteProtocol(@Param("tenantModelId") Long tenantModelId,@Param("protocol") String protocol);
    void insertRoute(@Param("tenantModelId") Long tenantModelId,@Param("protocol") String protocol,@Param("remark") String remark,@Param("userId") Long userId);
    void updateRoute(@Param("id") Long id,@Param("enabled") boolean enabled,@Param("remark") String remark,@Param("userId") Long userId);
    void softDeleteRoute(@Param("id") Long id,@Param("userId") Long userId);
    List<RouteTargetSummary> findRouteTargets(@Param("routeId") Long routeId);
    boolean validRouteTarget(@Param("routeId") Long routeId,@Param("channelId") Long channelId,@Param("channelModelId") Long channelModelId);
    void insertRouteTarget(@Param("routeId") Long routeId,@Param("channelId") Long channelId,@Param("channelModelId") Long channelModelId,@Param("priority") int priority,@Param("weight") int weight,@Param("remark") String remark,@Param("userId") Long userId);
    void updateRouteTarget(@Param("id") Long id,@Param("priority") int priority,@Param("weight") int weight,@Param("enabled") boolean enabled,@Param("remark") String remark,@Param("userId") Long userId);
    void softDeleteRouteTarget(@Param("id") Long id,@Param("userId") Long userId);
    List<PublicModelCatalogItem> findPublicModels(@Param("tenantId") Long tenantId);
    Optional<ChannelDiscoveryInfo> findChannelDiscoveryInfo(@Param("channelId") Long channelId);
    void upsertSyncedChannelModel(@Param("tenantId") Long tenantId,@Param("channelId") Long channelId,@Param("upstreamModelId") String upstreamModelId,@Param("credentialId") Long credentialId,@Param("userId") Long userId);
    List<String> findExistingChannelModelIds(@Param("channelId") Long channelId,@Param("upstreamModelIds") List<String> upstreamModelIds);
    void batchUpsertSyncedChannelModels(@Param("tenantId") Long tenantId,@Param("channelId") Long channelId,@Param("upstreamModelIds") List<String> upstreamModelIds,@Param("credentialId") Long credentialId,@Param("userId") Long userId);
}
