package io.fluxora.platform.tenant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface TenantMapper {

    Optional<Tenant> findByCode(@Param("tenantCode") String tenantCode);

    Optional<Tenant> findById(@Param("id") Long id);

    Optional<Tenant> findByIdIncludeDeleted(@Param("id") Long id);

    List<Tenant> findAll(@Param("keyword") String keyword,
                         @Param("type") String type,
                         @Param("status") String status,
                         @Param("expireFrom") Instant expireFrom,
                         @Param("expireTo") Instant expireTo,
                         @Param("offset") int offset,
                         @Param("limit") int limit);

    long countAll(@Param("keyword") String keyword,
                  @Param("type") String type,
                  @Param("status") String status,
                  @Param("expireFrom") Instant expireFrom,
                  @Param("expireTo") Instant expireTo);

    boolean existsByCode(@Param("tenantCode") String tenantCode);

    void insert(Tenant tenant);

    void update(Tenant tenant);

    void enableTenant(@Param("id") Long id);

    void disableTenant(@Param("id") Long id);

    void setExpireAt(@Param("id") Long id, @Param("expireAt") Instant expireAt);

    void softDelete(@Param("id") Long id);
}
