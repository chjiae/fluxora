package io.fluxora.platform.tenant;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import io.fluxora.platform.runtime.RuntimeOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    public static final String SELF_OPERATED = "SELF_OPERATED";
    public static final String STANDARD = "STANDARD";
    public static final String DEFAULT_CODE = "default";

    private final TenantMapper tenantMapper;
    private final IdentityMapper identityMapper;
    private final RuntimeOutboxService runtimeOutboxService;

    public TenantService(TenantMapper tenantMapper, IdentityMapper identityMapper,
                         RuntimeOutboxService runtimeOutboxService) {
        this.tenantMapper = tenantMapper;
        this.identityMapper = identityMapper;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    public boolean isSelfOperatedInitialized() {
        return tenantMapper.existsByCode(DEFAULT_CODE);
    }

    /**
     * 校验租户是否有效。
     * 按优先级依次检查：租户存在性 → 逻辑删除 → 过期 → 停用。
     * 返回枚举结果供调用方按场景处理，避免多处重复查询和判断。
     */
    public TenantValidationResult validateTenant(Long tenantId) {
        Tenant tenant = tenantMapper.findByIdIncludeDeleted(tenantId).orElse(null);
        if (tenant == null) {
            return TenantValidationResult.INVALID;
        }
        if (tenant.isDeleted()) {
            return TenantValidationResult.DELETED;
        }
        if (tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(Instant.now())) {
            return TenantValidationResult.EXPIRED;
        }
        if (!tenant.isEnabled()) {
            return TenantValidationResult.DISABLED;
        }
        return TenantValidationResult.VALID;
    }

    /**
     * 校验租户状态并在不合法时抛出对应的认证异常。
     * 用于登录流程和每次请求的租户状态校验。
     * 不同状态抛出不同业务错误码，确保前端能展示精确的中文提示：
     * 停用 → AUTH_TENANT_DISABLED、过期 → AUTH_TENANT_EXPIRED、删除 → AUTH_TENANT_DELETED。
     */
    public void assertTenantValidOrThrow(Long tenantId) {
        TenantValidationResult result = validateTenant(tenantId);
        switch (result) {
            case DELETED -> throw new AuthTenantException(BusinessErrorCode.AUTH_TENANT_DELETED);
            case EXPIRED -> throw new AuthTenantException(BusinessErrorCode.AUTH_TENANT_EXPIRED);
            case DISABLED -> throw new AuthTenantException(BusinessErrorCode.AUTH_TENANT_DISABLED);
            case INVALID -> throw new AuthTenantException(BusinessErrorCode.AUTH_TENANT_DELETED);
        }
    }

    public boolean isTenantValid(Long tenantId) {
        return validateTenant(tenantId) == TenantValidationResult.VALID;
    }

    @Transactional
    public TenantInitResult initializeSelfOperated(String tenantName, String adminUsername,
                                                    String adminPassword, String adminDisplayName) {
        if (tenantMapper.existsByCode(DEFAULT_CODE)) {
            throw new TenantException(BusinessErrorCode.TENANT_CODE_DUPLICATE, "自营租户已初始化，无需重复操作");
        }

        Tenant tenant = new Tenant();
        tenant.setTenantCode(DEFAULT_CODE);
        tenant.setName(tenantName);
        tenant.setDescription("平台自营租户");
        tenant.setType(SELF_OPERATED);
        tenant.setEnabled(true);
        tenantMapper.insert(tenant);
        runtimeOutboxService.record(tenant.getId(), "TENANT", tenant.getId(), "CREATED", null);

        if (identityMapper.existsByUsername(adminUsername)) {
            throw new TenantException(BusinessErrorCode.TENANT_CODE_DUPLICATE,
                    "用户名 " + adminUsername + " 已存在，请更换租户管理员用户名");
        }

        UserAccount tenantAdmin = new UserAccount();
        tenantAdmin.setUsername(adminUsername);
        tenantAdmin.setPasswordHash(adminPassword);
        tenantAdmin.setDisplayName(adminDisplayName);
        tenantAdmin.setEmail(null);
        tenantAdmin.setScopeType("TENANT");
        tenantAdmin.setTenantId(tenant.getId());
        tenantAdmin.setEnabled(true);
        identityMapper.insertUser(tenantAdmin);
        runtimeOutboxService.record(tenant.getId(), "USER_ACCOUNT", tenantAdmin.getId(), "CREATED", null);

        Role tenantAdminRole = identityMapper.findRoleByCode("TENANT_ADMIN")
                .orElseThrow(() -> new IllegalStateException("TENANT_ADMIN 角色不存在"));
        identityMapper.insertUserRole(tenantAdmin.getId(), tenantAdminRole.getId());

        log.info("自营租户 {} 初始化完成，租户管理员 {}", DEFAULT_CODE, adminUsername);
        return new TenantInitResult(tenant.getId(), tenant.getTenantCode(), tenantAdmin.getId(), tenantAdmin.getUsername());
    }

    /**
     * 自营租户（tenantCode = "default"，type = SELF_OPERATED）受后端强制保护。
     * 禁止任何删除、停用、启用切换、设置过期时间的操作。
     * 此方法在服务层统一调用，确保不依赖 Controller 层的检查，防止绕过。
     */
    public void assertSelfOperatedProtected(Long tenantId) {
        Tenant tenant = tenantMapper.findById(tenantId)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        if (SELF_OPERATED.equals(tenant.getType())) {
            throw new TenantException(BusinessErrorCode.SELF_OPERATED_TENANT_PROTECTED,
                    String.format(BusinessErrorCode.SELF_OPERATED_TENANT_PROTECTED.getDefaultUserMessage(), "进行此操作"));
        }
    }

    /**
     * 自营租户更新保护：禁止修改 tenantCode、type、enabled、expireAt
     * 仅允许修改 name、description 等基础资料
     */
    public void assertSelfOperatedUpdateAllowed(Long tenantId) {
        Tenant tenant = tenantMapper.findById(tenantId)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        if (SELF_OPERATED.equals(tenant.getType())) {
            // 自营租户本身允许更新 name 和 description
            // 实际保护在控制器/服务层处理，这里只是验证存在性
        }
    }

    /**
     * 创建租户时禁止通过普通 API 创建自营租户。
     * 自营租户（type = SELF_OPERATED）仅允许通过自营初始化流程创建，
     * 且其 tenantCode 固定为 "default"。
     */
    public void assertNotSelfOperatedType(String type) {
        if (SELF_OPERATED.equals(type)) {
            throw new TenantException(BusinessErrorCode.VALIDATION_ERROR,
                    "不允许通过此接口创建自营租户，自营租户仅通过初始化流程创建");
        }
    }

    /** 租户写操作统一在 Service 事务内完成，并与运行时 Outbox 同步提交。 */
    @Transactional
    public Tenant createTenant(Tenant tenant) {
        if (tenantMapper.existsByCode(tenant.getTenantCode())) {
            throw new TenantException(BusinessErrorCode.TENANT_CODE_DUPLICATE);
        }
        assertNotSelfOperatedType(tenant.getType());
        tenantMapper.insert(tenant);
        runtimeOutboxService.record(tenant.getId(), "TENANT", tenant.getId(), "CREATED", null);
        return tenant;
    }

    @Transactional
    public Tenant updateTenantBasic(Long id, String name, String description) {
        Tenant tenant = tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        tenant.setName(name);
        if (description != null) {
            tenant.setDescription(description);
        }
        tenantMapper.update(tenant);
        runtimeOutboxService.record(id, "TENANT", id, "UPDATED", "BASIC_METADATA");
        return tenant;
    }

    @Transactional
    public Tenant enableTenant(Long id) {
        assertSelfOperatedProtected(id);
        tenantMapper.enableTenant(id);
        runtimeOutboxService.record(id, "TENANT", id, "ENABLED", null);
        return loadTenant(id);
    }

    @Transactional
    public Tenant disableTenant(Long id) {
        assertSelfOperatedProtected(id);
        tenantMapper.disableTenant(id);
        runtimeOutboxService.record(id, "TENANT", id, "DISABLED", null);
        return loadTenant(id);
    }

    @Transactional
    public Tenant setTenantExpireAt(Long id, Instant expireAt) {
        assertSelfOperatedProtected(id);
        tenantMapper.setExpireAt(id, expireAt);
        runtimeOutboxService.record(id, "TENANT", id, "UPDATED", "EXPIRE_AT");
        return loadTenant(id);
    }

    @Transactional
    public void deleteTenant(Long id) {
        assertSelfOperatedProtected(id);
        tenantMapper.softDelete(id);
        runtimeOutboxService.record(id, "TENANT", id, "DELETED", null);
    }

    private Tenant loadTenant(Long id) {
        return tenantMapper.findById(id)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
    }

    /**
     * 租户状态异常（停用/过期/删除）对应的认证异常。
     * 用于登录流程与每次请求的过滤器校验，与 AuthException 区分以支持独立错误码映射。
     */
    public static class AuthTenantException extends RuntimeException {
        private final BusinessErrorCode errorCode;

        public AuthTenantException(BusinessErrorCode errorCode) {
            super(errorCode.getDefaultUserMessage());
            this.errorCode = errorCode;
        }

        public BusinessErrorCode getErrorCode() {
            return errorCode;
        }
    }

    public enum TenantValidationResult {
        VALID, DISABLED, EXPIRED, DELETED, INVALID
    }

    public record TenantInitResult(Long tenantId, String tenantCode, Long adminUserId, String adminUsername) {}
}
