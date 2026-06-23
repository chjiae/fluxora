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

    /**
     * 同步后更新候选的上次同步时间，并清空 last_sync_summary。
     * 仅更新指定通道+上游标识、未删除的候选。 */
    void updateLastSyncedAt(@Param("channelId") Long channelId,
                            @Param("upstreamModelId") String upstreamModelId);

    /**
     * 同步后标记「本次未返回」的候选（不物理删除）；
     * 通过 NOT IN 子句精确匹配「不在本次同步上游 ID 集合中」的候选。
     * 候选保留全部信息（候选 / 映射 / 路由不受影响），仅更新 last_sync_summary 摘要。 */
    long markMissing(@Param("channelId") Long channelId,
                     @Param("upstreamIds") java.util.Collection<String> upstreamIds,
                     @Param("summary") String summary,
                     @Param("updatedBy") Long updatedBy);

    /** 查询某个候选当前是否仍被本租户任何未删除的候选映射引用。 */
    long countActiveMappings(@Param("channelModelId") Long channelModelId);
}
