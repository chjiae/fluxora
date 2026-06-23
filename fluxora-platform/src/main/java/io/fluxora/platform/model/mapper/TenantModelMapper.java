package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.TenantModel;
import io.fluxora.platform.model.dto.TenantModelStats;
import io.fluxora.platform.model.dto.TenantModelSummary;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户模型数据访问契约。
 * 所有查询默认追加 deleted_at IS NULL；指标条数据通过单次聚合 SQL 计算，不允许前端遍历列表自统计。
 */
@Mapper
public interface TenantModelMapper {

    void insert(TenantModel entity);

    /** 仅返回未删除记录。 */
    Optional<TenantModel> findById(@Param("id") Long id);

    /** 列表投影：联表租户名称、当前有效映射计数与是否存在有效价格；不返回任何已删除记录。 */
    List<TenantModelSummary> findPage(@Param("tenantId") Long tenantId,
                                      @Param("platform") boolean platform,
                                      @Param("keyword") String keyword,
                                      @Param("status") String status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    long countPage(@Param("tenantId") Long tenantId,
                   @Param("platform") boolean platform,
                   @Param("keyword") String keyword,
                   @Param("status") String status);

    Optional<TenantModelSummary> findSummaryById(@Param("id") Long id);

    /** 同租户内 model_code 是否被未删除记录占用（创建/更新校验）。 */
    int existsActiveByCode(@Param("tenantId") Long tenantId,
                           @Param("modelCode") String modelCode,
                           @Param("excludeId") Long excludeId);

    /** 聚合指标：单次 SQL 计算 total/enabled/disabled/draft/missingPrice/missingRoute。 */
    TenantModelStats stats(@Param("tenantId") Long tenantId,
                           @Param("platform") boolean platform);

    void updateBasics(TenantModel entity);

    /** 切换发布状态：DRAFT / ENABLED / DISABLED；同步维护 enabled 冗余列。 */
    void setPublishStatus(@Param("id") Long id,
                         @Param("publishStatus") String publishStatus,
                         @Param("enabled") boolean enabled,
                         @Param("updatedBy") Long updatedBy);

    void softDelete(@Param("id") Long id, @Param("updatedBy") Long updatedBy);
}
