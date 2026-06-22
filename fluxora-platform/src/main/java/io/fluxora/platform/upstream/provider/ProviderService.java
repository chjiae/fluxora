package io.fluxora.platform.upstream.provider;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 厂商作用域与共享资源写保护统一在服务层执行，不能依赖前端隐藏按钮。 */
@Service
public class ProviderService {
    private final ProviderMapper mapper;
    public ProviderService(ProviderMapper mapper) { this.mapper = mapper; }
    public boolean isPlatformAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }
    public Provider requireVisible(Long id, UserAccount user, Authentication authentication) {
        return mapper.findVisibleById(id, user.getTenantId(), isPlatformAdmin(authentication))
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游厂商不存在或不可访问"));
    }
    public void assertWritable(Provider provider, UserAccount user, Authentication authentication) {
        if ("PLATFORM_SHARED".equals(provider.getScopeType()) && !isPlatformAdmin(authentication)) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号无权修改平台共享上游配置");
        }
        if ("TENANT_PRIVATE".equals(provider.getScopeType()) && !isPlatformAdmin(authentication)
                && !provider.getTenantId().equals(user.getTenantId())) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
    }
    @Transactional
    public Provider create(Provider provider, UserAccount user, Authentication authentication) {
        boolean platform = isPlatformAdmin(authentication);
        if ("PLATFORM_SHARED".equals(provider.getScopeType()) && !platform) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号无权创建平台共享上游配置");
        }
        if (!platform || "TENANT_PRIVATE".equals(provider.getScopeType())) provider.setTenantId(user.getTenantId());
        if (provider.getTenantId() == null && !"PLATFORM_SHARED".equals(provider.getScopeType())) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择有效的上游配置范围");
        }
        mapper.insert(provider); return provider;
    }
    @Transactional public Provider update(Long id, Provider patch, UserAccount user, Authentication auth) {
        Provider current = requireVisible(id, user, auth); assertWritable(current, user, auth);
        current.setName(patch.getName()); current.setDescription(patch.getDescription()); mapper.update(current); return current;
    }
}
