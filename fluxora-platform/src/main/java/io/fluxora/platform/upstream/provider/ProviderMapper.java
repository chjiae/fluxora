package io.fluxora.platform.upstream.provider;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 上游厂商访问接口；所有 SQL 实现在 MyBatis XML，禁止注解 SQL。 */
@Mapper
public interface ProviderMapper {
    void insert(Provider provider);
    Optional<Provider> findById(@Param("id") Long id);
    Optional<Provider> findVisibleById(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin);
    List<Provider> findPage(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin, @Param("keyword") String keyword, @Param("scopeType") String scopeType, @Param("enabled") Boolean enabled, @Param("offset") int offset, @Param("limit") int limit);
    long countPage(@Param("tenantId") Long tenantId, @Param("platformAdmin") boolean platformAdmin, @Param("keyword") String keyword, @Param("scopeType") String scopeType, @Param("enabled") Boolean enabled);
    void update(Provider provider);
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDelete(@Param("id") Long id);
    boolean hasBaseUrls(@Param("id") Long id);
}
