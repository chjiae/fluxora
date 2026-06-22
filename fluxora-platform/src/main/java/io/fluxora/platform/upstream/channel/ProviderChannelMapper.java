package io.fluxora.platform.upstream.channel;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.fluxora.platform.upstream.channel.dto.ProviderChannelStats;
import io.fluxora.platform.upstream.channel.mapper.ProviderChannelRow;

/**
 * 上游通道数据访问契约。
 * 列表查询通过一次 JOIN 取出关联展示字段与凭证数量，避免逐行加载（N+1）。
 * 所有 SQL 在 MyBatis XML，禁止注解 SQL。
 */
@Mapper
public interface ProviderChannelMapper {
    void insert(ProviderChannel channel);

    /** 内部加载：仅返回未删除通道，租户归属由服务层校验。 */
    Optional<ProviderChannel> findById(@Param("id") Long id);

    /** 列表行：含租户、厂商、协议、地址与凭证数量。 */
    List<ProviderChannelRow> findRows(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin,
            @Param("keyword") String keyword, @Param("providerId") Long providerId, @Param("protocol") String protocol,
            @Param("enabled") Boolean enabled, @Param("offset") int offset, @Param("limit") int limit);

    long countRows(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin,
            @Param("keyword") String keyword, @Param("providerId") Long providerId, @Param("protocol") String protocol,
            @Param("enabled") Boolean enabled);

    Optional<ProviderChannelRow> findRowById(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin);

    void update(ProviderChannel channel);
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDelete(@Param("id") Long id);
    boolean hasCredentials(@Param("channelId") Long channelId);

    /** 通道聚合指标，单次 COUNT(*) FILTER，过滤删除行与可见作用域。 */
    ProviderChannelStats stats(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin);
}
