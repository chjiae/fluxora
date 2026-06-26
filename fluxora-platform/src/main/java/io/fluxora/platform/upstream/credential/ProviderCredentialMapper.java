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
    List<ProviderCredential> insertBatchReturningCredentials(@Param("items") List<ProviderCredential> items);

    /** 在同一租户通道创建有效绑定；绑定关系才决定该通道的凭证池是否可用。 */
    void bindCredential(@Param("tenantId") Long tenantId,
                        @Param("channelId") Long channelId,
                        @Param("credentialId") Long credentialId);

    /** 批量导入后单条 SQL 批量创建绑定，禁止逐个调用 Mapper。 */
    void bindBatch(@Param("tenantId") Long tenantId,
                   @Param("channelId") Long channelId,
                   @Param("credentials") List<ProviderCredential> credentials);

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

    /**
     * 一次 IN 查询当前租户当前通道已存在且未软删除的指纹，避免逐条查询（N+1）。
     * excludeId 可选：替换场景下排除自身避免误判重复。
     */
    List<String> findActiveFingerprints(@Param("tenantId") Long tenantId,
                                        @Param("channelId") Long channelId,
                                        @Param("fingerprints") Collection<String> fingerprints,
                                        @Param("excludeId") Long excludeId);

    /** 用于显式复用同租户同 Provider 的已有凭证，返回值仍不含密文。 */
    Optional<ProviderCredential> findActiveByFingerprint(@Param("tenantId") Long tenantId,
                                                          @Param("fingerprint") String fingerprint);
    boolean hasActiveBinding(@Param("channelId") Long channelId, @Param("credentialId") Long credentialId);
    long countActiveBindings(@Param("credentialId") Long credentialId);
    void setBindingEnabled(@Param("channelId") Long channelId, @Param("credentialId") Long credentialId,
                           @Param("enabled") boolean enabled);
    void softDeleteBinding(@Param("channelId") Long channelId, @Param("credentialId") Long credentialId);

    void updateMetadata(ProviderCredential credential);
    void replaceSecret(ProviderCredential credential);
    void setEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    void softDelete(@Param("id") Long id);

    /**
     * 批量软删除凭证。
     * 使用一条 UPDATE ... WHERE id IN (...) 完成，禁止循环调用 softDelete。
     * 限定租户与未删除状态，返回实际影响行数。
     */
    int softDeleteBatch(@Param("tenantId") Long tenantId, @Param("ids") List<Long> ids);

    /** 指定通道下凭证聚合指标。 */
    ProviderCredentialStats stats(@Param("channelId") Long channelId);
}
