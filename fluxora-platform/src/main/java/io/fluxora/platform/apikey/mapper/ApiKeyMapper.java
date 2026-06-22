package io.fluxora.platform.apikey.mapper;

import io.fluxora.platform.apikey.ApiKey;
import io.fluxora.platform.apikey.dto.ApiKeyStats;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * API Key MyBatis Mapper。
 * 所有 SQL 写在 ApiKeyMapper.xml；查询默认过滤 deleted_at IS NULL。
 */
@Mapper
public interface ApiKeyMapper {

    /** 按 ID 查询 ApiKey（含 owner 校验所需字段；仅未软删记录） */
    Optional<ApiKey> findById(@Param("id") Long id);

    /** 分页查询 ApiKey 行（含用户/租户 join） */
    List<ApiKeyRow> findRows(@Param("tenantId") Long tenantId,
                             @Param("userId") Long userId,
                             @Param("keyword") String keyword,
                             @Param("status") String status,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    /** 统计符合条件的 ApiKey 行数 */
    long countRows(@Param("tenantId") Long tenantId,
                   @Param("userId") Long userId,
                   @Param("keyword") String keyword,
                   @Param("status") String status);

    /** 单条详情（用于创建后立刻回查脱敏视图） */
    Optional<ApiKeyRow> findRowById(@Param("id") Long id);

    /** 插入 ApiKey；返回自增 id 由 useGeneratedKeys 写回实体 */
    void insert(ApiKey apiKey);

    /** 更新 name 与 expireAt；expireAt 为 null 表示"清空"或"保持不变"由调用方决定 */
    void updateMeta(@Param("id") Long id,
                    @Param("name") String name,
                    @Param("expireAt") Instant expireAt,
                    @Param("clearExpire") boolean clearExpire);

    /** 设置 enabled */
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);

    /** 软删除 */
    void softDelete(@Param("id") Long id);

    /**
     * 聚合统计：可按租户 / 用户 / 全平台过滤；用 COUNT(*) FILTER 单 SQL 返回。
     * tenantId 为 null 表示全平台；userId 为 null 表示不按用户过滤。
     */
    ApiKeyStats stats(@Param("tenantId") Long tenantId,
                      @Param("userId") Long userId,
                      @Param("expiringWithinDays") int expiringWithinDays);
}
