package io.fluxora.platform.upstream.credential;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.fluxora.platform.upstream.credential.dto.ProviderCredentialStats;

/**
 * 凭证数据访问契约。
 * 读取列表与详情时只选择脱敏与元数据列，绝不选择密文、随机向量和指纹。
 * 批量导入使用一次 IN 查询已存在指纹与多行 INSERT，禁止循环单条写入。
 */
@Mapper
public interface ProviderCredentialMapper {
    void insert(ProviderCredential credential);

    /**
     * 批量写入并返回实际插入行的指纹。
     * 使用 INSERT ... ON CONFLICT (...) DO NOTHING RETURNING，单条 SQL 完成批量写入与并发兜底；
     * 返回值用于区分本请求成功写入的行与因并发已存在被跳过的行，避免逐条查询（N+1）。
     */
    List<String> insertBatchReturningFingerprints(@Param("items") List<ProviderCredential> items);

    /** 元数据投影：不含密文、随机向量、指纹、加密版本、deleted_at。 */
    Optional<ProviderCredential> findMetadataById(@Param("id") Long id);

    /** 内部投影：含密文与指纹，仅供替换与内部解密路径使用，禁止从 Controller 调用。 */
    Optional<ProviderCredential> findInternalById(@Param("id") Long id);
    /** 仅模型同步内部路径读取一个已启用凭证；严禁 Controller 或响应 DTO 调用。 */
    Optional<ProviderCredential> findFirstEnabledInternalByChannel(@Param("channelId") Long channelId);

    List<ProviderCredential> findPage(@Param("channelId") Long channelId, @Param("keyword") String keyword,
            @Param("maskedValue") String maskedValue, @Param("enabled") Boolean enabled, @Param("offset") int offset, @Param("limit") int limit);

    long countPage(@Param("channelId") Long channelId, @Param("keyword") String keyword,
            @Param("maskedValue") String maskedValue, @Param("enabled") Boolean enabled);

    /** 一次 IN 查询当前租户已存在且未软删除的指纹，避免逐条查询（N+1）。 */
    List<String> findActiveFingerprints(@Param("tenantId") Long tenantId, @Param("fingerprints") Collection<String> fingerprints);

    void updateMetadata(ProviderCredential credential);
    void replaceSecret(ProviderCredential credential);
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDelete(@Param("id") Long id);

    /** 指定通道下凭证聚合指标。 */
    ProviderCredentialStats stats(@Param("channelId") Long channelId);
}
