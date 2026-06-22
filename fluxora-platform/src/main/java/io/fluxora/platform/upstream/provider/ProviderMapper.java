package io.fluxora.platform.upstream.provider;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.fluxora.platform.upstream.provider.dto.ProviderBaseUrlStats;
import io.fluxora.platform.upstream.provider.dto.ProviderStats;
import io.fluxora.platform.upstream.provider.mapper.ProviderRow;

/**
 * 上游厂商与接入地址数据访问契约。
 * 所有 SQL 实现在 MyBatis XML，禁止注解 SQL。
 * 公开列表与详情查询不选择 deleted_at；逻辑删除统一通过 softDelete 写入。
 */
@Mapper
public interface ProviderMapper {
    // ---------------- Provider ----------------
    void insert(Provider provider);

    /** 详情/内部加载：仅返回未删除厂商。 */
    Optional<Provider> findVisibleById(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin);

    /** 列表行：含归属租户名称，已过滤删除行与可见作用域。 */
    List<ProviderRow> findRows(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin,
            @Param("keyword") String keyword, @Param("scopeType") String scopeType, @Param("enabled") Boolean enabled,
            @Param("offset") int offset, @Param("limit") int limit);

    long countRows(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin,
            @Param("keyword") String keyword, @Param("scopeType") String scopeType, @Param("enabled") Boolean enabled);

    /** 单行详情：含租户名称。 */
    Optional<ProviderRow> findRowById(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin);

    /** 编码重复预检：避免依赖数据库唯一索引报错返回 500。 */
    boolean existsByCode(@Param("code") String code);

    void update(Provider provider);
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDelete(@Param("id") Long id);
    boolean hasBaseUrls(@Param("id") Long id);

    /** 厂商聚合指标，单次 COUNT(*) FILTER，过滤删除行与可见作用域。 */
    ProviderStats stats(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin);

    // ---------------- ProviderBaseUrl ----------------
    void insertBaseUrl(ProviderBaseUrl baseUrl);
    Optional<ProviderBaseUrl> findBaseUrlById(@Param("id") Long id);

    /** 指定厂商下的接入地址列表，已过滤删除行。 */
    List<ProviderBaseUrl> findBaseUrls(@Param("providerId") Long providerId);

    void updateBaseUrl(ProviderBaseUrl baseUrl);
    void setBaseUrlEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDeleteBaseUrl(@Param("id") Long id);
    boolean hasChannelsByBaseUrl(@Param("id") Long id);

    /** 同厂商同协议同规范化地址重复预检。 */
    boolean existsBaseUrl(@Param("providerId") Long providerId, @Param("protocol") String protocol, @Param("normalizedBaseUrl") String normalizedBaseUrl, @Param("excludeId") Long excludeId);

    /** 指定厂商下接入地址聚合指标。 */
    ProviderBaseUrlStats baseUrlStats(@Param("providerId") Long providerId);
}
