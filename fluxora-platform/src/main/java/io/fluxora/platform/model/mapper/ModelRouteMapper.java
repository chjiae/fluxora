package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.ModelRoute;
import io.fluxora.platform.model.dto.ModelRouteSummary;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 模型路由数据访问契约。
 * 所有查询追加 deleted_at IS NULL；同一 tenantModelId + inboundProtocol 未删除唯一性
 * 由部分唯一索引 uk_model_route_active 兜底。
 */
@Mapper
public interface ModelRouteMapper {

    void insert(ModelRoute entity);

    Optional<ModelRoute> findById(@Param("id") Long id);

    /** 列表投影：联表当前路由下未删除目标数量，避免前端 N+1。 */
    List<ModelRouteSummary> findByTenantModel(@Param("tenantModelId") Long tenantModelId);

    /** 同模型同协议未删除唯一检查（创建场景）。 */
    int existsActiveByProtocol(@Param("tenantModelId") Long tenantModelId,
                               @Param("inboundProtocol") String inboundProtocol,
                               @Param("excludeId") Long excludeId);

    void setEnabled(@Param("id") Long id,
                    @Param("enabled") boolean enabled,
                    @Param("updatedBy") Long updatedBy);

    void updateRemark(@Param("id") Long id,
                      @Param("remark") String remark,
                      @Param("updatedBy") Long updatedBy);

    void softDelete(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    /** 当前模型下未删除路由数量；用于启用前置判定。 */
    long countActiveByTenantModel(@Param("tenantModelId") Long tenantModelId);

    /** 当前模型下任一未删除路由包含至少一个未删除 RouteTarget 即视为「有路由目标」。 */
    long countActiveTargetsByTenantModel(@Param("tenantModelId") Long tenantModelId);
}
