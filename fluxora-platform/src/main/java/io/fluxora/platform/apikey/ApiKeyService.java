package io.fluxora.platform.apikey;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.apikey.dto.ApiKeyPageResponse;
import io.fluxora.platform.apikey.dto.ApiKeyQuery;
import io.fluxora.platform.apikey.dto.ApiKeyStats;
import io.fluxora.platform.apikey.dto.ApiKeySummary;
import io.fluxora.platform.apikey.dto.CreateApiKeyRequest;
import io.fluxora.platform.apikey.dto.CreatedApiKeyResponse;
import io.fluxora.platform.apikey.dto.UpdateApiKeyRequest;
import io.fluxora.platform.apikey.mapper.ApiKeyMapper;
import io.fluxora.platform.apikey.mapper.ApiKeyRow;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.tenant.Tenant;
import io.fluxora.platform.tenant.TenantException;
import io.fluxora.platform.tenant.TenantMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API Key 服务。
 *
 * 权限边界（强制于此层；@PreAuthorize 仅做粗粒度网关）：
 *
 *   - 普通租户用户：仅可读写自己的 Key（user_id 必须 == currentUser.id）；
 *   - 租户管理员：可读写本租户全部用户的 Key（tenant_id 必须 == currentUser.tenantId）；
 *   - 平台管理员：可读写任意租户的 Key；本身 PLATFORM 作用域不持有 Key。
 *
 * 跨租户保护：所有按 ID 的操作先 {@code findById} 取出 Key，再用
 * {@link #assertCanOperate(UserAccount, ApiKey)} 校验归属；客户端传入的 user_id /
 * tenant_id 路径参数永远只作冗余，不被信任。
 *
 * 安全：完整明文 Key 仅在 {@link #createKey} 的返回值中出现一次；其后任何接口、
 * 列表、详情都不再返回；本服务也不会把它写入日志、堆栈、异常消息。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    /** Key 名称：长度 2-64，允许字母、数字、空格、下划线、连字符、点号与常见中文 */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9 _.\\-\\u4e00-\\u9fa5]{2,64}$");

    private final ApiKeyMapper apiKeyMapper;
    private final IdentityMapper identityMapper;
    private final TenantMapper tenantMapper;
    private final ApiKeyHashingService hashingService;
    private final ApiKeyGenerator generator;

    public ApiKeyService(ApiKeyMapper apiKeyMapper,
                         IdentityMapper identityMapper,
                         TenantMapper tenantMapper,
                         ApiKeyHashingService hashingService,
                         ApiKeyGenerator generator) {
        this.apiKeyMapper = apiKeyMapper;
        this.identityMapper = identityMapper;
        this.tenantMapper = tenantMapper;
        this.hashingService = hashingService;
        this.generator = generator;
    }

    // ============================================================
    // 列表 / 详情 / 统计
    // ============================================================

    /**
     * 列表查询。Scope 由调用入口决定：
     *   - SELF：service 强制 userId = currentUser.id，忽略 query.userId / query.tenantId；
     *   - TENANT(tid)：tenant 路径，校验当前用户对 tid 的访问权限；
     *   - PLATFORM：跨租户，可按 query.tenantId / query.userId 进一步过滤。
     */
    @Transactional(readOnly = true)
    public ApiKeyPageResponse listKeys(UserAccount currentUser, Scope scope, ApiKeyQuery q) {
        ScopeFilter f = resolveListScope(currentUser, scope, q);
        int page = q.pageOrDefault();
        int size = q.sizeOrDefault();
        int offset = (page - 1) * size;

        List<ApiKeyRow> rows = apiKeyMapper.findRows(
                f.tenantId, f.userId, blankToNull(q.keyword()), blankToNull(q.status()),
                offset, size);
        long total = apiKeyMapper.countRows(
                f.tenantId, f.userId, blankToNull(q.keyword()), blankToNull(q.status()));

        List<ApiKeySummary> items = rows.stream().map(this::toSummary).toList();
        return new ApiKeyPageResponse(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public ApiKeyStats getStats(UserAccount currentUser, Scope scope) {
        return getStats(currentUser, scope, null);
    }

    /**
     * 聚合统计；scope=TENANT 时需显式传入 explicitTenantId（来自路径），
     * 让 resolveListScope 用同款逻辑校验归属与解析过滤组合。
     */
    @Transactional(readOnly = true)
    public ApiKeyStats getStats(UserAccount currentUser, Scope scope, Long explicitTenantId) {
        ApiKeyQuery q = new ApiKeyQuery(null, null, null, explicitTenantId, null, null);
        ScopeFilter f = resolveListScope(currentUser, scope, q);
        return apiKeyMapper.stats(f.tenantId, f.userId, 30);
    }

    @Transactional(readOnly = true)
    public ApiKeySummary getKey(UserAccount currentUser, Long keyId) {
        ApiKey key = loadKeyOrThrow(keyId);
        assertCanOperate(currentUser, key);
        return apiKeyMapper.findRowById(keyId).map(this::toSummary)
                .orElseThrow(() -> new ApiKeyException(BusinessErrorCode.API_KEY_NOT_FOUND));
    }

    // ============================================================
    // 创建
    // ============================================================

    /**
     * 创建 API Key。
     *
     * @param currentUser 当前登录用户
     * @param routeTenantId 来自路径的租户 ID（{@code /api/tenant/{tenantId}/api-keys}）；
     *                      自身路径（{@code /api/api-keys}）传 null
     * @param req 请求体；req.forUserId 仅在管理员路径生效，普通用户路径被忽略
     */
    @Transactional
    public CreatedApiKeyResponse createKey(UserAccount currentUser,
                                            Long routeTenantId,
                                            CreateApiKeyRequest req) {
        if (req == null || isBlank(req.name())) {
            throw new ApiKeyException(BusinessErrorCode.API_KEY_NAME_INVALID);
        }
        assertNameValid(req.name());

        UserAccount target = resolveCreateTarget(currentUser, routeTenantId, req.forUserId());
        // 目标用户的租户必须可写（与 MemberService 写操作一致）
        assertTenantWritable(target.getTenantId());

        Instant expireAt = parseInstant(req.expireAt());

        ApiKeyGenerator.GeneratedKey gen = generator.generate();
        ApiKey key = new ApiKey();
        key.setTenantId(target.getTenantId());
        key.setUserId(target.getId());
        key.setName(req.name().trim());
        key.setKeyPrefix(gen.prefix());
        key.setKeyHash(hashingService.hash(gen.secretPart()));
        key.setEnabled(true);
        key.setExpireAt(expireAt);
        apiKeyMapper.insert(key);

        log.info("API Key 已创建：tenantId={}, userId={}, keyId={}, prefix={}",
                target.getTenantId(), target.getId(), key.getId(), gen.prefix());

        ApiKeyRow row = apiKeyMapper.findRowById(key.getId())
                .orElseThrow(() -> new ApiKeyException(BusinessErrorCode.INTERNAL_ERROR));
        // plaintext 仅在此处返回；不写日志、不入持久化、不入其他接口
        return new CreatedApiKeyResponse(toSummary(row), gen.plaintext());
    }

    // ============================================================
    // 编辑 / 启停 / 删除
    // ============================================================

    @Transactional
    public ApiKeySummary updateKey(UserAccount currentUser, Long keyId, UpdateApiKeyRequest req) {
        ApiKey key = loadKeyOrThrow(keyId);
        assertCanOperate(currentUser, key);
        assertTenantWritable(key.getTenantId());

        String name = (req == null || isBlank(req.name())) ? key.getName() : req.name().trim();
        assertNameValid(name);

        String action = req == null ? null : req.expireAtAction();
        Instant expire;
        boolean clearExpire;
        if ("CLEAR".equalsIgnoreCase(action)) {
            expire = null;
            clearExpire = true;
        } else if ("SET".equalsIgnoreCase(action)) {
            expire = parseInstant(req.expireAt());
            clearExpire = false;
        } else {
            // 未提供 action：保持原值；mapper 通过 clearExpire=false + expireAt=旧值 实现
            expire = key.getExpireAt();
            clearExpire = false;
        }

        apiKeyMapper.updateMeta(keyId, name, expire, clearExpire);
        return loadSummaryOrThrow(keyId);
    }

    @Transactional
    public ApiKeySummary enableKey(UserAccount currentUser, Long keyId) {
        ApiKey key = loadKeyOrThrow(keyId);
        assertCanOperate(currentUser, key);
        assertTenantWritable(key.getTenantId());
        apiKeyMapper.setEnabled(keyId, true);
        return loadSummaryOrThrow(keyId);
    }

    @Transactional
    public ApiKeySummary disableKey(UserAccount currentUser, Long keyId) {
        ApiKey key = loadKeyOrThrow(keyId);
        assertCanOperate(currentUser, key);
        assertTenantWritable(key.getTenantId());
        apiKeyMapper.setEnabled(keyId, false);
        return loadSummaryOrThrow(keyId);
    }

    @Transactional
    public void deleteKey(UserAccount currentUser, Long keyId) {
        ApiKey key = loadKeyOrThrow(keyId);
        assertCanOperate(currentUser, key);
        assertTenantWritable(key.getTenantId());
        apiKeyMapper.softDelete(keyId);
        log.info("API Key 已软删除：keyId={}", keyId);
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    /** 访问作用域：调用入口（controller）决定，传给 service */
    public enum Scope { SELF, TENANT, PLATFORM }

    /** 解析列表查询的 (tenantId, userId) 过滤组合 */
    private record ScopeFilter(Long tenantId, Long userId) {}

    private ScopeFilter resolveListScope(UserAccount currentUser, Scope scope, ApiKeyQuery q) {
        switch (scope) {
            case SELF -> {
                if (!"TENANT".equals(currentUser.getScopeType()) || currentUser.getTenantId() == null) {
                    // 平台用户没有"自己的 Key"概念
                    throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                }
                return new ScopeFilter(currentUser.getTenantId(), currentUser.getId());
            }
            case TENANT -> {
                Long requested = q.tenantId();
                if (requested == null) {
                    throw new ApiKeyException(BusinessErrorCode.VALIDATION_ERROR);
                }
                if (!isPlatformAdmin(currentUser)) {
                    if (currentUser.getTenantId() == null
                            || !Objects.equals(currentUser.getTenantId(), requested)) {
                        throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                    }
                    if (!isTenantAdmin(currentUser)) {
                        throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                    }
                }
                return new ScopeFilter(requested, q.userId());
            }
            case PLATFORM -> {
                if (!isPlatformAdmin(currentUser)) {
                    throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
                }
                return new ScopeFilter(q.tenantId(), q.userId());
            }
            default -> throw new ApiKeyException(BusinessErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * 解析创建 Key 的目标用户：
     *   - 普通租户用户：强制 = currentUser；忽略 routeTenantId 与 forUserId；
     *   - 租户管理员：必须在自身租户内；forUserId 可为本租户内任意 TENANT 用户；
     *   - 平台管理员：必须显式指定 routeTenantId + forUserId。
     */
    private UserAccount resolveCreateTarget(UserAccount currentUser, Long routeTenantId, Long forUserId) {
        if (isPlatformAdmin(currentUser)) {
            if (routeTenantId == null || forUserId == null) {
                throw new ApiKeyException(BusinessErrorCode.VALIDATION_ERROR);
            }
            UserAccount target = identityMapper.findById(forUserId)
                    .orElseThrow(() -> new ApiKeyException(BusinessErrorCode.RESOURCE_NOT_FOUND));
            if (!"TENANT".equals(target.getScopeType()) || target.getTenantId() == null
                    || !Objects.equals(target.getTenantId(), routeTenantId)) {
                throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            return target;
        }
        if (isTenantAdmin(currentUser)) {
            Long ownTenant = currentUser.getTenantId();
            if (ownTenant == null) {
                throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            if (routeTenantId != null && !ownTenant.equals(routeTenantId)) {
                throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            if (forUserId == null) {
                // 租户管理员不指定 forUserId 时，默认给自己创建
                return currentUser;
            }
            UserAccount target = identityMapper.findById(forUserId)
                    .orElseThrow(() -> new ApiKeyException(BusinessErrorCode.RESOURCE_NOT_FOUND));
            if (!"TENANT".equals(target.getScopeType())
                    || !Objects.equals(target.getTenantId(), ownTenant)) {
                throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
            }
            return target;
        }
        // 普通租户用户：强制为自身
        if ("TENANT".equals(currentUser.getScopeType()) && currentUser.getTenantId() != null) {
            return currentUser;
        }
        throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    /**
     * 校验当前用户对某个 Key 的操作权限。
     * 平台管理员通过；租户管理员需与 Key 同租户；普通用户需与 Key 同 user_id。
     */
    private void assertCanOperate(UserAccount currentUser, ApiKey key) {
        if (isPlatformAdmin(currentUser)) return;
        if (isTenantAdmin(currentUser)) {
            if (currentUser.getTenantId() != null
                    && Objects.equals(currentUser.getTenantId(), key.getTenantId())) return;
            throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
        }
        if (Objects.equals(currentUser.getId(), key.getUserId())) return;
        throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED);
    }

    private void assertTenantWritable(Long tenantId) {
        Tenant t = tenantMapper.findByIdIncludeDeleted(tenantId).orElse(null);
        if (t == null || t.isDeleted()) {
            throw new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        if (!t.isEnabled()) {
            throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED, "租户已停用");
        }
        if (t.getExpireAt() != null && t.getExpireAt().isBefore(Instant.now())) {
            throw new ApiKeyException(BusinessErrorCode.CROSS_TENANT_ACCESS_DENIED, "租户已过期");
        }
    }

    private void assertNameValid(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ApiKeyException(BusinessErrorCode.API_KEY_NAME_INVALID);
        }
    }

    private ApiKey loadKeyOrThrow(Long id) {
        return apiKeyMapper.findById(id)
                .orElseThrow(() -> new ApiKeyException(BusinessErrorCode.API_KEY_NOT_FOUND));
    }

    private ApiKeySummary loadSummaryOrThrow(Long id) {
        return apiKeyMapper.findRowById(id).map(this::toSummary)
                .orElseThrow(() -> new ApiKeyException(BusinessErrorCode.API_KEY_NOT_FOUND));
    }

    private ApiKeySummary toSummary(ApiKeyRow row) {
        return new ApiKeySummary(
                row.getId(), row.getTenantId(), row.getTenantCode(), row.getTenantName(),
                row.getUserId(), row.getUsername(), row.getUserDisplayName(),
                row.getName(), row.getKeyPrefix(), row.getStatus(),
                row.getExpireAt(), row.getLastUsedAt(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private boolean isPlatformAdmin(UserAccount user) {
        if (user == null || !"PLATFORM".equals(user.getScopeType())) return false;
        return identityMapper.findRolesByUserId(user.getId()).stream()
                .anyMatch(r -> PLATFORM_ADMIN_ROLE.equals(r.getCode()));
    }

    private boolean isTenantAdmin(UserAccount user) {
        if (user == null || !"TENANT".equals(user.getScopeType())) return false;
        return identityMapper.findRolesByUserId(user.getId()).stream()
                .anyMatch(r -> TENANT_ADMIN_ROLE.equals(r.getCode()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            throw new ApiKeyException(BusinessErrorCode.VALIDATION_ERROR);
        }
    }
}
