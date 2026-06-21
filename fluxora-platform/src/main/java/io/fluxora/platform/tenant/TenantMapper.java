package io.fluxora.platform.tenant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TenantMapper {

    Optional<Tenant> findByCode(@Param("tenantCode") String tenantCode);

    Optional<Tenant> findById(@Param("id") Long id);

    List<Tenant> findAll(@Param("keyword") String keyword,
                         @Param("type") String type,
                         @Param("enabled") Boolean enabled,
                         @Param("offset") int offset,
                         @Param("limit") int limit);

    long countAll(@Param("keyword") String keyword,
                  @Param("type") String type,
                  @Param("enabled") Boolean enabled);

    boolean existsByCode(@Param("tenantCode") String tenantCode);

    void insert(Tenant tenant);

    void update(Tenant tenant);

    void softDelete(@Param("id") Long id);
}
