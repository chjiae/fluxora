package io.fluxora.platform.upstream.security;

import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.tenant.Tenant;
import io.fluxora.platform.tenant.TenantMapper;
import io.fluxora.platform.upstream.provider.ProviderException;

/**
 * 上游配置共享的租户可用性校验。
 * 平台管理员为目标租户配置私有上游时，同样必须确认目标租户未删除、未停用、未过期，
 * 避免向不可用租户写入配置。
 */
@Component
public class UpstreamTenantGuard {

    private final TenantMapper tenantMapper;

    public UpstreamTenantGuard(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    /** 复用已加载的 JWT 权限判定，避免额外查询；ROLE_PLATFORM_ADMIN 由认证过滤器注入。 */
    public boolean isPlatformAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    /** 校验目标租户可写：存在、未删除、未停用、未过期。 */
    public void assertWritable(Long tenantId) {
        if (tenantId == null) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择目标租户");
        }
        Tenant tenant = tenantMapper.findByIdIncludeDeleted(tenantId)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "租户不存在"));
        if (tenant.isDeleted() || !tenant.isEnabled()
                || (tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(Instant.now()))) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_TENANT_UNAVAILABLE, "所属租户当前不可用");
        }
    }
}
