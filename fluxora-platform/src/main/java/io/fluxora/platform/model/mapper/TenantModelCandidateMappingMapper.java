package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.TenantModelCandidateMapping;
import io.fluxora.platform.model.dto.TenantModelCandidateMappingSummary;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户模型候选映射数据访问契约。
 * 写入前必须由服务层保证 tenant_model.tenant_id == provider_channel_model.tenant_id == 入参 tenant_id。
 * 删除前必须由服务层保证未被启用的 route_target 引用（提交 4 后引入 route_target 后会校验）。
 */
@Mapper
public interface TenantModelCandidateMappingMapper {

    void insert(TenantModelCandidateMapping entity);

    Optional<TenantModelCandidateMapping> findById(@Param("id") Long id);

    /** 列表投影：联表候选与所属通道，便于前端表格直接展示，不返回已删除记录。 */
    List<TenantModelCandidateMappingSummary> findByTenantModel(@Param("tenantModelId") Long tenantModelId);

    /** 同一租户模型与同一候选未删除映射唯一性检查。 */
    int existsActivePair(@Param("tenantModelId") Long tenantModelId,
                         @Param("providerChannelModelId") Long providerChannelModelId);

    void setEnabled(@Param("id") Long id,
                    @Param("enabled") boolean enabled,
                    @Param("updatedBy") Long updatedBy);

    void updateRemark(@Param("id") Long id,
                      @Param("remark") String remark,
                      @Param("updatedBy") Long updatedBy);

    void softDelete(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    /** 当前租户模型下未删除映射数量；用于发布前校验「至少一个候选支撑」。 */
    long countActiveByTenantModel(@Param("tenantModelId") Long tenantModelId);

    /**
     * 列出当前租户模型下所有「映射启用 + 候选启用 + 通道启用 + 全部未删除」的候选实体；
     * 仅返回必要能力列与上游标识，供租户模型启用前的能力支撑判定使用。
     */
    List<io.fluxora.platform.model.ProviderChannelModel> findActiveSupportingCandidates(
            @Param("tenantModelId") Long tenantModelId);
}
