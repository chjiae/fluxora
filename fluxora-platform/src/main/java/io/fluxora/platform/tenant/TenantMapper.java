package io.fluxora.platform.tenant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 租户相关的 MyBatis Mapper。
 * 所有 SQL 均写在对应的 TenantMapper.xml 中，禁止注解 SQL。
 */
@Mapper
public interface TenantMapper {

    /** 按租户码查询租户（仅未删除记录） */
    Optional<Tenant> findByCode(@Param("tenantCode") String tenantCode);

    /** 按 ID 查询租户（仅未删除记录） */
    Optional<Tenant> findById(@Param("id") Long id);

    /** 按 ID 查询租户（含已删除记录，用于认证阶段校验租户是否被逻辑删除） */
    Optional<Tenant> findByIdIncludeDeleted(@Param("id") Long id);

    /** 分页查询租户列表，支持关键词搜索、类型筛选、状态筛选、过期时间范围筛选 */
    List<Tenant> findAll(@Param("keyword") String keyword,
                         @Param("type") String type,
                         @Param("status") String status,
                         @Param("expireFrom") Instant expireFrom,
                         @Param("expireTo") Instant expireTo,
                         @Param("offset") int offset,
                         @Param("limit") int limit);

    /** 统计符合条件的租户总数（分页用） */
    long countAll(@Param("keyword") String keyword,
                  @Param("type") String type,
                  @Param("status") String status,
                  @Param("expireFrom") Instant expireFrom,
                  @Param("expireTo") Instant expireTo);

    /** 检查租户码是否已存在 */
    boolean existsByCode(@Param("tenantCode") String tenantCode);

    /** 新增租户 */
    void insert(Tenant tenant);

    /** 更新租户基础信息（仅 name、description） */
    void update(Tenant tenant);

    /** 启用租户 */
    void enableTenant(@Param("id") Long id);

    /** 停用租户 */
    void disableTenant(@Param("id") Long id);

    /** 设置租户过期时间，expireAt 为 null 表示永不过期 */
    void setExpireAt(@Param("id") Long id, @Param("expireAt") Instant expireAt);

    /** 逻辑删除租户（写入 deleted_at = NOW()），软删除遵循 AGENT.md 软删除规范 */
    void softDelete(@Param("id") Long id);
}
