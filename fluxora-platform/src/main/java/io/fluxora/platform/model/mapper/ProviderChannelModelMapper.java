package io.fluxora.platform.model.mapper;

import io.fluxora.platform.model.ProviderChannelModel;
import io.fluxora.platform.model.dto.ProviderChannelModelSummary;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 上游候选数据访问契约。
 * 所有查询默认追加 deleted_at IS NULL；候选与所属通道的租户一致性由服务层强制兜底。
 */
@Mapper
public interface ProviderChannelModelMapper {

    void insert(ProviderChannelModel entity);

    Optional<ProviderChannelModel> findById(@Param("id") Long id);

    List<ProviderChannelModelSummary> findByChannel(@Param("channelId") Long channelId);

    int existsActiveByUpstreamId(@Param("channelId") Long channelId,
                                 @Param("upstreamModelId") String upstreamModelId,
                                 @Param("excludeId") Long excludeId);

    void updateBasics(ProviderChannelModel entity);

    void setEnabled(@Param("id") Long id,
                    @Param("enabled") boolean enabled,
                    @Param("updatedBy") Long updatedBy);

    void softDelete(@Param("id") Long id, @Param("updatedBy") Long updatedBy);

    /** 查询某个候选当前是否仍被本租户任何未删除的候选映射引用。 */
    long countActiveMappings(@Param("channelModelId") Long channelModelId);
}
