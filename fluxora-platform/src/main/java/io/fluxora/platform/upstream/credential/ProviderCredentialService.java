package io.fluxora.platform.upstream.credential;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.channel.ProviderChannel;
import io.fluxora.platform.upstream.channel.ProviderChannelService;
import io.fluxora.platform.upstream.credential.dto.CredentialImportItem;
import io.fluxora.platform.upstream.credential.dto.CredentialImportItemResult;
import io.fluxora.platform.upstream.credential.dto.CredentialImportRequest;
import io.fluxora.platform.upstream.credential.dto.CredentialImportResult;
import io.fluxora.platform.upstream.credential.dto.CredentialImportSummary;
import io.fluxora.platform.upstream.credential.dto.ProviderCredentialStats;
import io.fluxora.platform.upstream.credential.dto.ProviderCredentialSummary;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import io.fluxora.platform.upstream.provider.ProviderException;
import io.fluxora.platform.upstream.security.CredentialCryptoService;
import io.fluxora.platform.upstream.security.CredentialSecurityProperties;
import io.fluxora.platform.upstream.security.EncryptedCredential;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;

/**
 * 上游凭证服务。
 *
 * 安全要点：
 *   - 明文仅在创建、替换或导入请求处理期间存在；处理完成后服务不再持有，且绝不写入日志、异常或 DTO。
 *   - 公开 DTO 永远只包含脱敏值与元数据，绝不包含 ciphertext、initializationVector、fingerprint、encryptionVersion、deletedAt。
 *   - 重复判定范围固定为当前租户未软删除凭证；停用凭证也算已存在；软删除后可重新导入。
 *   - 批量导入使用批内 Set 去重、一次 IN 查询已存在指纹、一次批量 INSERT...RETURNING 写入，禁止循环单条调用 Mapper。
 *
 * 租户隔离：所有操作先通过 {@link ProviderChannelService#requireVisible} 校验通道归属，
 * 凭证 tenantId 强制取自通道，客户端不可指定。
 */
@Service
public class ProviderCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialService.class);
    private static final String CREDENTIAL_TYPE = "API_KEY";
    private static final int DEFAULT_PRIORITY = 100;
    private static final int DEFAULT_WEIGHT = 100;

    private final ProviderCredentialMapper mapper;
    private final ProviderChannelService channelService;
    private final CredentialCryptoService cryptoService;
    private final CredentialSecurityProperties securityProperties;
    private final UpstreamTenantGuard tenantGuard;

    public ProviderCredentialService(ProviderCredentialMapper mapper, ProviderChannelService channelService,
            CredentialCryptoService cryptoService, CredentialSecurityProperties securityProperties,
            UpstreamTenantGuard tenantGuard) {
        this.mapper = mapper;
        this.channelService = channelService;
        this.cryptoService = cryptoService;
        this.securityProperties = securityProperties;
        this.tenantGuard = tenantGuard;
    }

    // ==================== 列表 / 详情 / 统计 ====================

    @Transactional(readOnly = true)
    public UpstreamPage<ProviderCredentialSummary> list(Long channelId, UserAccount user, Authentication auth,
            String keyword, String maskedValue, Boolean enabled, int page, int size) {
        ProviderChannel channel = channelService.requireVisible(channelId, user, auth);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<ProviderCredential> rows = mapper.findPage(channel.getId(), blankToNull(keyword),
                blankToNull(maskedValue), enabled, (safePage - 1) * safeSize, safeSize);
        long total = mapper.countPage(channel.getId(), blankToNull(keyword), blankToNull(maskedValue), enabled);
        return new UpstreamPage<>(rows.stream().map(this::toSummary).toList(), total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ProviderCredentialSummary detail(Long id, UserAccount user, Authentication auth) {
        ProviderCredential credential = loadMetadataOrThrow(id);
        channelService.requireVisible(credential.getProviderChannelId(), user, auth);
        return toSummary(credential);
    }

    @Transactional(readOnly = true)
    public ProviderCredentialStats stats(Long channelId, UserAccount user, Authentication auth) {
        ProviderChannel channel = channelService.requireVisible(channelId, user, auth);
        return mapper.stats(channel.getId());
    }

    // ==================== 创建 / 编辑 / 替换 / 启停 / 删除 ====================

    @Transactional
    public ProviderCredentialSummary create(CreateFields fields, UserAccount user, Authentication auth) {
        ProviderChannel channel = channelService.requireVisible(fields.providerChannelId(), user, auth);
        tenantGuard.assertWritable(channel.getTenantId());
        String plaintext = requirePlaintext(fields.plaintext());

        ProviderCredential credential = buildCredential(channel.getTenantId(), channel.getId(),
                resolveName(fields.name(), plaintext), fields.priority(), fields.weight(), fields.remark());
        EncryptedCredential encrypted = cryptoService.encrypt(plaintext);
        String fingerprint = cryptoService.fingerprint(plaintext);
        applySecret(credential, cryptoService.mask(plaintext), fingerprint, encrypted);

        // 预检当前租户未删除指纹，避免依赖唯一索引报错返回 500；并发冲突由 catch 兜底。
        if (fingerprintExists(channel.getTenantId(), fingerprint)) {
            throw new ProviderException(BusinessErrorCode.CREDENTIAL_DUPLICATE, "凭证已存在");
        }
        try {
            mapper.insert(credential);
        } catch (DataIntegrityViolationException ex) {
            // 并发写入同一指纹命中部分唯一索引
            throw new ProviderException(BusinessErrorCode.CREDENTIAL_DUPLICATE, "凭证已存在");
        }
        log.info("上游凭证已创建：channelId={}, credentialId={}", channel.getId(), credential.getId());
        return detail(credential.getId(), user, auth);
    }

    @Transactional
    public ProviderCredentialSummary updateMetadata(Long id, UpdateFields fields, UserAccount user, Authentication auth) {
        ProviderCredential credential = loadMetadataOrThrow(id);
        ProviderChannel channel = channelService.requireVisible(credential.getProviderChannelId(), user, auth);
        tenantGuard.assertWritable(channel.getTenantId());
        // 元数据编辑不影响密文：未提交新凭证时绝不覆盖已有密文。
        credential.setName(resolveName(fields.name(), null));
        credential.setPriority(fields.priority() == null ? credential.getPriority() : fields.priority());
        credential.setWeight(fields.weight() == null ? credential.getWeight() : fields.weight());
        credential.setRemark(blankToNull(fields.remark()));
        validateMetadata(credential);
        mapper.updateMetadata(credential);
        return detail(id, user, auth);
    }

    @Transactional
    public ProviderCredentialSummary replaceSecret(Long id, String plaintext, UserAccount user, Authentication auth) {
        ProviderCredential credential = loadInternalOrThrow(id);
        ProviderChannel channel = channelService.requireVisible(credential.getProviderChannelId(), user, auth);
        tenantGuard.assertWritable(channel.getTenantId());
        String newPlaintext = requirePlaintext(plaintext);
        EncryptedCredential encrypted = cryptoService.encrypt(newPlaintext);
        String newFingerprint = cryptoService.fingerprint(newPlaintext);

        // 新明文若与当前指纹相同，直接更新密文即可（同值重新加密，不触发唯一冲突）。
        // 若与当前租户其他未删除凭证重复，拒绝替换。
        if (!newFingerprint.equals(credential.getCredentialFingerprint())
                && fingerprintExists(channel.getTenantId(), newFingerprint)) {
            throw new ProviderException(BusinessErrorCode.CREDENTIAL_DUPLICATE, "新凭证已存在");
        }
        applySecret(credential, cryptoService.mask(newPlaintext), newFingerprint, encrypted);
        try {
            mapper.replaceSecret(credential);
        } catch (DataIntegrityViolationException ex) {
            throw new ProviderException(BusinessErrorCode.CREDENTIAL_REPLACE_FAILED, "凭证替换冲突");
        }
        log.info("上游凭证已替换密文：credentialId={}", id);
        return detail(id, user, auth);
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        ProviderCredential credential = loadMetadataOrThrow(id);
        ProviderChannel channel = channelService.requireVisible(credential.getProviderChannelId(), user, auth);
        tenantGuard.assertWritable(channel.getTenantId());
        mapper.setEnabled(id, enabled);
    }

    @Transactional
    public void delete(Long id, UserAccount user, Authentication auth) {
        ProviderCredential credential = loadMetadataOrThrow(id);
        ProviderChannel channel = channelService.requireVisible(credential.getProviderChannelId(), user, auth);
        tenantGuard.assertWritable(channel.getTenantId());
        mapper.softDelete(id);
        log.info("上游凭证已软删除：credentialId={}", id);
    }

    // ==================== 批量导入 ====================

    /**
     * 批量导入凭证。
     * 集合化处理：批内 Set 去重 → 一次 IN 查询已存在指纹 → 一次批量 INSERT...RETURNING 写入。
     * 禁止逐行调用 Mapper。结果仅返回行号、脱敏标识与安全原因，不含明文。
     */
    @Transactional
    public CredentialImportResult importCredentials(CredentialImportRequest req, UserAccount user, Authentication auth) {
        if (req == null || req.providerChannelId() == null) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择目标通道");
        }
        ProviderChannel channel = channelService.requireVisible(req.providerChannelId(), user, auth);
        tenantGuard.assertWritable(channel.getTenantId());

        int maxCount = securityProperties.getImportMaxCount();
        int priority = req.priority() == null ? DEFAULT_PRIORITY : req.priority();
        int weight = req.weight() == null ? DEFAULT_WEIGHT : req.weight();
        String remark = blankToNull(req.remark());

        List<CredentialImportItem> items = new ArrayList<>();
        // 批内指纹去重：保留首次出现行，后续重复标记跳过。
        Map<String, Candidate> uniqueCandidates = new LinkedHashMap<>();
        Set<String> seenInBatch = new HashSet<>();
        int invalid = 0;
        int overLimit = 0;

        List<String> lines = req.lines() == null ? List.of() : req.lines();
        int lineNumber = 0;
        for (String raw : lines) {
            lineNumber++;
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                invalid++;
                items.add(new CredentialImportItem(lineNumber, null, CredentialImportItemResult.INVALID, "凭证格式不正确"));
                continue;
            }
            // 超过单次导入上限的非空行不再处理，统一标记超限，避免超大请求与超长响应。
            if (uniqueCandidates.size() >= maxCount) {
                overLimit++;
                items.add(new CredentialImportItem(lineNumber, null, CredentialImportItemResult.OVER_LIMIT, "超过单次导入数量限制"));
                continue;
            }
            String fingerprint = cryptoService.fingerprint(trimmed);
            if (!seenInBatch.add(fingerprint)) {
                items.add(new CredentialImportItem(lineNumber, cryptoService.mask(trimmed),
                        CredentialImportItemResult.SKIPPED_BATCH_DUPLICATE, "同一批次内重复"));
                continue;
            }
            uniqueCandidates.put(fingerprint, new Candidate(lineNumber, trimmed, fingerprint, cryptoService.mask(trimmed)));
        }

        // 一次 IN 查询当前租户已存在且未软删除的指纹。
        Set<String> existing = uniqueCandidates.isEmpty()
                ? Set.of()
                : new HashSet<>(mapper.findActiveFingerprints(channel.getTenantId(), uniqueCandidates.keySet()));

        List<ProviderCredential> toInsert = new ArrayList<>();
        for (Candidate c : uniqueCandidates.values()) {
            if (existing.contains(c.fingerprint)) {
                // 已启用或已停用但未软删除的凭证都视为已存在，跳过；不覆盖、不启用、不更新。
                // 结果项在写入完成后统一归位，避免重复添加。
                continue;
            }
            ProviderCredential credential = buildCredential(channel.getTenantId(), channel.getId(),
                    resolveName(req.namePrefix(), c.plaintext), priority, weight, remark);
            EncryptedCredential encrypted = cryptoService.encrypt(c.plaintext);
            applySecret(credential, c.maskedValue, c.fingerprint, encrypted);
            toInsert.add(credential);
        }

        // 一次批量写入；RETURNING 返回实际插入行的指纹，用于区分并发冲突。
        Set<String> inserted = new HashSet<>();
        if (!toInsert.isEmpty()) {
            inserted.addAll(mapper.insertBatchReturningFingerprints(toInsert));
        }

        int imported = 0;
        int concurrent = 0;
        // 遍历候选，按是否实际插入归位结果项；existing 已在上一段标记 SKIPPED_EXISTING。
        for (Candidate c : uniqueCandidates.values()) {
            if (existing.contains(c.fingerprint)) {
                items.add(new CredentialImportItem(c.lineNumber, c.maskedValue,
                        CredentialImportItemResult.SKIPPED_EXISTING, "当前租户已存在相同凭证"));
                continue;
            }
            if (inserted.contains(c.fingerprint)) {
                imported++;
                items.add(new CredentialImportItem(c.lineNumber, c.maskedValue,
                        CredentialImportItemResult.IMPORTED, "导入成功"));
            } else {
                // 未在 existing 也未被 RETURNING 返回：并发写入导致唯一索引冲突
                concurrent++;
                items.add(new CredentialImportItem(c.lineNumber, c.maskedValue,
                        CredentialImportItemResult.SKIPPED_CONCURRENT, "导入过程中已存在，已跳过"));
            }
        }

        // 行号排序，便于前端逐行展示
        items.sort(java.util.Comparator.comparingInt(CredentialImportItem::lineNumber));

        int totalRead = items.size();
        CredentialImportSummary summary = new CredentialImportSummary(
                totalRead,
                imported,
                countByResult(items, CredentialImportItemResult.SKIPPED_BATCH_DUPLICATE),
                countByResult(items, CredentialImportItemResult.SKIPPED_EXISTING),
                invalid,
                overLimit,
                concurrent);
        log.info("凭证批量导入完成：channelId={}, imported={}, existing={}, batchDuplicate={}, invalid={}, overLimit={}, concurrent={}",
                channel.getId(), imported, summary.skippedExisting(), summary.skippedBatchDuplicate(),
                invalid, overLimit, concurrent);
        return new CredentialImportResult(summary, items);
    }

    // ==================== 内部辅助 ====================

    private int countByResult(List<CredentialImportItem> items, CredentialImportItemResult result) {
        int n = 0;
        for (CredentialImportItem i : items) {
            if (i.result() == result) n++;
        }
        return n;
    }

    private boolean fingerprintExists(Long tenantId, String fingerprint) {
        return !mapper.findActiveFingerprints(tenantId, List.of(fingerprint)).isEmpty();
    }

    private ProviderCredential loadMetadataOrThrow(Long id) {
        return mapper.findMetadataById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "凭证不存在或已删除"));
    }

    private ProviderCredential loadInternalOrThrow(Long id) {
        return mapper.findInternalById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "凭证不存在或已删除"));
    }

    private void applySecret(ProviderCredential credential, String maskedValue, String fingerprint,
            EncryptedCredential encrypted) {
        credential.setMaskedValue(maskedValue);
        credential.setCredentialFingerprint(fingerprint);
        credential.setCiphertext(encrypted.ciphertext());
        credential.setInitializationVector(encrypted.initializationVector());
        credential.setEncryptionVersion(encrypted.encryptionVersion());
    }

    private ProviderCredential buildCredential(Long tenantId, Long channelId, String name, Integer priority,
            Integer weight, String remark) {
        ProviderCredential credential = new ProviderCredential();
        credential.setTenantId(tenantId);
        credential.setProviderChannelId(channelId);
        credential.setName(name);
        credential.setCredentialType(CREDENTIAL_TYPE);
        credential.setEnabled(true);
        credential.setPriority(priority == null ? DEFAULT_PRIORITY : priority);
        credential.setWeight(weight == null ? DEFAULT_WEIGHT : weight);
        credential.setRemark(remark);
        validateMetadata(credential);
        return credential;
    }

    private String resolveName(String provided, String plaintext) {
        if (provided != null && !provided.isBlank()) {
            String trimmed = provided.trim();
            return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
        }
        // 未提供名称时使用脱敏值或固定前缀，避免要求逐条填写名称
        return "凭证-" + (plaintext == null ? "" : cryptoService.mask(plaintext));
    }

    private String requirePlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new ProviderException(BusinessErrorCode.CREDENTIAL_REQUIRED, "凭证明文为空");
        }
        return plaintext.trim();
    }

    private void validateMetadata(ProviderCredential c) {
        if (c.getName() == null || c.getName().isBlank() || c.getName().length() > 128
                || c.getPriority() < 0 || c.getPriority() > 100000
                || c.getWeight() < 1 || c.getWeight() > 100000) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "凭证元数据不合法");
        }
    }

    private ProviderCredentialSummary toSummary(ProviderCredential c) {
        return new ProviderCredentialSummary(c.getId(), c.getTenantId(), c.getProviderChannelId(),
                c.getName(), c.getCredentialType(), c.getMaskedValue(),
                c.isEnabled() ? "ENABLED" : "DISABLED", c.getPriority(), c.getWeight(),
                c.getRemark(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 导入候选行：保留原始明文仅用于加密，处理完成后随方法栈释放。 */
    private record Candidate(int lineNumber, String plaintext, String fingerprint, String maskedValue) {
    }

    /** 创建请求字段。 */
    public record CreateFields(Long providerChannelId, String plaintext, String name, Integer priority, Integer weight, String remark) {
    }

    /** 元数据编辑字段。 */
    public record UpdateFields(String name, Integer priority, Integer weight, String remark) {
    }
}
