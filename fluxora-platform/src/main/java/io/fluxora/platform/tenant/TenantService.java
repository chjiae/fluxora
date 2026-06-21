package io.fluxora.platform.tenant;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.Role;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.identity.mapper.IdentityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final String SELF_OPERATED = "SELF_OPERATED";
    private static final String DEFAULT_CODE = "default";

    private final TenantMapper tenantMapper;
    private final IdentityMapper identityMapper;

    public TenantService(TenantMapper tenantMapper, IdentityMapper identityMapper) {
        this.tenantMapper = tenantMapper;
        this.identityMapper = identityMapper;
    }

    public boolean isSelfOperatedInitialized() {
        return tenantMapper.existsByCode(DEFAULT_CODE);
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
        tenant.setType(SELF_OPERATED);
        tenant.setEnabled(true);
        tenantMapper.insert(tenant);

        // 创建租户管理员用户
        if (identityMapper.existsByUsername(adminUsername)) {
            throw new TenantException(BusinessErrorCode.TENANT_CODE_DUPLICATE,
                    "用户名 " + adminUsername + " 已存在，请更换租户管理员用户名");
        }

        UserAccount tenantAdmin = new UserAccount();
        tenantAdmin.setUsername(adminUsername);
        tenantAdmin.setPasswordHash(adminPassword); // 已由控制器 BCrypt 加密
        tenantAdmin.setDisplayName(adminDisplayName);
        tenantAdmin.setEmail(null);
        tenantAdmin.setScopeType("TENANT");
        tenantAdmin.setTenantId(tenant.getId());
        tenantAdmin.setEnabled(true);
        identityMapper.insertUser(tenantAdmin);

        Role tenantAdminRole = identityMapper.findRoleByCode("TENANT_ADMIN")
                .orElseThrow(() -> new IllegalStateException("TENANT_ADMIN 角色不存在"));
        identityMapper.insertUserRole(tenantAdmin.getId(), tenantAdminRole.getId());

        log.info("自营租户 {} 初始化完成，租户管理员 {}", DEFAULT_CODE, adminUsername);

        return new TenantInitResult(tenant.getId(), tenant.getTenantCode(), tenantAdmin.getId(), tenantAdmin.getUsername());
    }

    public void assertSelfOperatedProtected(Long tenantId) {
        Tenant tenant = tenantMapper.findById(tenantId)
                .orElseThrow(() -> new TenantException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        if (SELF_OPERATED.equals(tenant.getType())) {
            throw new TenantException(BusinessErrorCode.SELF_OPERATED_TENANT_PROTECTED,
                    String.format(BusinessErrorCode.SELF_OPERATED_TENANT_PROTECTED.getDefaultUserMessage(), "进行此操作"));
        }
    }

    public boolean isTenantValid(Long tenantId) {
        Tenant tenant = tenantMapper.findById(tenantId).orElse(null);
        if (tenant == null) return false;
        if (!tenant.isEnabled()) return false;
        if (tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(Instant.now())) return false;
        return true;
    }

    public record TenantInitResult(Long tenantId, String tenantCode, Long adminUserId, String adminUsername) {}
}
