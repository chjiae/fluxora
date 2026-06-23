package io.fluxora.platform.upstream.provider;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.ProviderBaseUrlNormalizer;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import io.fluxora.platform.upstream.provider.dto.ProviderBaseUrlStats;
import io.fluxora.platform.upstream.provider.dto.ProviderBaseUrlSummary;
import io.fluxora.platform.upstream.provider.dto.ProviderStats;
import io.fluxora.platform.upstream.provider.dto.ProviderSummary;
import io.fluxora.platform.upstream.provider.mapper.ProviderRow;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import io.fluxora.platform.runtime.RuntimeOutboxService;

/**
 * 上游厂商与接入地址服务。
 *
 * 作用域与写保护统一在此层执行，不依赖前端隐藏按钮：
 *   - PLATFORM_SHARED：仅平台管理员可写；所有租户可读。
 *   - TENANT_PRIVATE：仅归属租户与平台管理员可见可写。
 *
 * 租户管理员的客户端 tenantId 永远不被信任：私有资源强制归属当前租户，
 * 平台管理员为目标租户配置私有资源时也必须通过 {@link UpstreamTenantGuard} 确认租户可用。
 */
@Service
public class ProviderService {

    private final ProviderMapper mapper;
    private final ProviderBaseUrlNormalizer normalizer;
    private final UpstreamTenantGuard tenantGuard;
    private final RuntimeOutboxService runtimeOutboxService;

    public ProviderService(ProviderMapper mapper, ProviderBaseUrlNormalizer normalizer, UpstreamTenantGuard tenantGuard,
                           RuntimeOutboxService runtimeOutboxService) {
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.tenantGuard = tenantGuard;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    public boolean isPlatformAdmin(Authentication auth) {
        return tenantGuard.isPlatformAdmin(auth);
    }

    // ==================== Provider ====================

    @Transactional(readOnly = true)
    public UpstreamPage<ProviderSummary> listProviders(UserAccount user, Authentication auth,
            String keyword, String scopeType, Boolean enabled, int page, int size) {
        boolean platform = isPlatformAdmin(auth);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<ProviderRow> rows = mapper.findRows(user.getTenantId(), platform,
                blankToNull(keyword), blankToNull(scopeType), enabled, (safePage - 1) * safeSize, safeSize);
        long total = mapper.countRows(user.getTenantId(), platform,
                blankToNull(keyword), blankToNull(scopeType), enabled);
        return new UpstreamPage<>(rows.stream().map(this::toSummary).toList(), total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ProviderSummary providerDetail(Long id, UserAccount user, Authentication auth) {
        return mapper.findRowById(id, user.getTenantId(), isPlatformAdmin(auth))
                .map(this::toSummary)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游厂商不存在或不可访问"));
    }

    @Transactional(readOnly = true)
    public ProviderStats providerStats(UserAccount user, Authentication auth) {
        return mapper.stats(user.getTenantId(), isPlatformAdmin(auth));
    }

    @Transactional
    public ProviderSummary createProvider(String name, String code, String scopeType, String description, Boolean enabled,
            Long targetTenantId, UserAccount user, Authentication auth) {
        boolean platform = isPlatformAdmin(auth);
        String normalizedScope = blankToNull(scopeType);
        if (normalizedScope == null || (!"PLATFORM_SHARED".equals(normalizedScope) && !"TENANT_PRIVATE".equals(normalizedScope))) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择有效的上游配置范围");
        }
        if ("PLATFORM_SHARED".equals(normalizedScope) && !platform) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_SHARED_READONLY, "当前账号无权创建平台共享上游配置");
        }
        validateName(name);
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请填写有效的上游厂商编码");
        }
        if (mapper.existsByCode(code.trim())) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_PROVIDER_CODE_DUPLICATE, "上游厂商编码已存在");
        }

        Provider provider = new Provider();
        provider.setName(name.trim());
        provider.setCode(code.trim());
        provider.setScopeType(normalizedScope);
        provider.setDescription(blankToNull(description));
        provider.setEnabled(enabled == null || enabled);
        if ("TENANT_PRIVATE".equals(normalizedScope)) {
            // 租户管理员强制归属本租户，忽略客户端传入的 tenantId；平台管理员必须显式指定目标租户。
            Long targetTenant = platform ? targetTenantId : user.getTenantId();
            if (targetTenant == null) {
                throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择私有上游归属的目标租户");
            }
            if (!platform && !targetTenant.equals(user.getTenantId())) {
                throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
            }
            tenantGuard.assertWritable(targetTenant);
            provider.setTenantId(targetTenant);
        }
        mapper.insert(provider);
        runtimeOutboxService.record(provider.getTenantId(), "PROVIDER", provider.getId(), "CREATED", null);
        return providerDetail(provider.getId(), user, auth);
    }

    @Transactional
    public ProviderSummary updateProvider(Long id, String name, String description, UserAccount user, Authentication auth) {
        Provider current = requireVisible(id, user, auth);
        assertWritable(current, user, auth);
        validateName(name);
        current.setName(name.trim());
        current.setDescription(blankToNull(description));
        mapper.update(current);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER", id, "UPDATED", null);
        return providerDetail(id, user, auth);
    }

    @Transactional
    public void setProviderEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        Provider current = requireVisible(id, user, auth);
        assertWritable(current, user, auth);
        mapper.setEnabled(id, enabled);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER", id, enabled ? "ENABLED" : "DISABLED", null);
    }

    @Transactional
    public void deleteProvider(Long id, UserAccount user, Authentication auth) {
        Provider current = requireVisible(id, user, auth);
        assertWritable(current, user, auth);
        if (mapper.hasBaseUrls(id)) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_PROVIDER_IN_USE, "上游厂商仍被接入地址引用");
        }
        mapper.softDelete(id);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER", id, "DELETED", null);
    }

    /** 内部可见性加载，供写操作前置校验。 */
    public Provider requireVisible(Long id, UserAccount user, Authentication auth) {
        return mapper.findVisibleById(id, user.getTenantId(), isPlatformAdmin(auth))
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游厂商不存在或不可访问"));
    }

    /** 共享资源仅平台管理员可写；私有资源仅平台管理员或归属租户可写。 */
    public void assertWritable(Provider provider, UserAccount user, Authentication auth) {
        if ("PLATFORM_SHARED".equals(provider.getScopeType()) && !isPlatformAdmin(auth)) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_SHARED_READONLY, "当前账号无权修改平台共享上游配置");
        }
        if ("TENANT_PRIVATE".equals(provider.getScopeType()) && !isPlatformAdmin(auth)
                && !provider.getTenantId().equals(user.getTenantId())) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
    }

    // ==================== ProviderBaseUrl ====================

    @Transactional(readOnly = true)
    public List<ProviderBaseUrlSummary> listBaseUrls(Long providerId, UserAccount user, Authentication auth) {
        requireVisible(providerId, user, auth);
        return mapper.findBaseUrls(providerId).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public ProviderBaseUrlStats baseUrlStats(Long providerId, UserAccount user, Authentication auth) {
        requireVisible(providerId, user, auth);
        return mapper.baseUrlStats(providerId);
    }

    @Transactional
    public ProviderBaseUrlSummary createBaseUrl(Long providerId, String protocol, String baseUrl,
            String displayName, String remark, UserAccount user, Authentication auth) {
        Provider provider = requireVisible(providerId, user, auth);
        assertWritable(provider, user, auth);
        String normalized = normalizeUrl(baseUrl);
        String proto = validateProtocol(protocol);
        if (mapper.existsBaseUrl(providerId, proto, normalized, null)) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_BASE_URL_DUPLICATE, "同协议同地址已存在");
        }
        ProviderBaseUrl entity = new ProviderBaseUrl();
        entity.setProviderId(providerId);
        entity.setProtocol(proto);
        entity.setOriginalBaseUrl(baseUrl.trim());
        entity.setNormalizedBaseUrl(normalized);
        entity.setDisplayName(blankToNull(displayName));
        entity.setRemark(blankToNull(remark));
        entity.setEnabled(true);
        mapper.insertBaseUrl(entity);
        runtimeOutboxService.record(provider.getTenantId(), "PROVIDER_BASE_URL", entity.getId(), "CREATED", null);
        return toSummary(mapper.findBaseUrlById(entity.getId())
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.INTERNAL_ERROR, "接入地址创建失败")));
    }

    @Transactional
    public ProviderBaseUrlSummary updateBaseUrl(Long id, String protocol, String baseUrl,
            String displayName, String remark, UserAccount user, Authentication auth) {
        ProviderBaseUrl current = mapper.findBaseUrlById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "接入地址不存在或不可访问"));
        Provider provider = requireVisible(current.getProviderId(), user, auth);
        assertWritable(provider, user, auth);
        String normalized = normalizeUrl(baseUrl);
        String proto = validateProtocol(protocol);
        if (mapper.existsBaseUrl(current.getProviderId(), proto, normalized, id)) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_BASE_URL_DUPLICATE, "同协议同地址已存在");
        }
        current.setProtocol(proto);
        current.setOriginalBaseUrl(baseUrl.trim());
        current.setNormalizedBaseUrl(normalized);
        current.setDisplayName(blankToNull(displayName));
        current.setRemark(blankToNull(remark));
        mapper.updateBaseUrl(current);
        runtimeOutboxService.record(provider.getTenantId(), "PROVIDER_BASE_URL", id, "UPDATED", null);
        return toSummary(mapper.findBaseUrlById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "接入地址不存在或不可访问")));
    }

    @Transactional
    public void setBaseUrlEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        ProviderBaseUrl current = mapper.findBaseUrlById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "接入地址不存在或不可访问"));
        Provider provider = requireVisible(current.getProviderId(), user, auth);
        assertWritable(provider, user, auth);
        mapper.setBaseUrlEnabled(id, enabled);
        runtimeOutboxService.record(provider.getTenantId(), "PROVIDER_BASE_URL", id,
                enabled ? "ENABLED" : "DISABLED", null);
    }

    @Transactional
    public void deleteBaseUrl(Long id, UserAccount user, Authentication auth) {
        ProviderBaseUrl current = mapper.findBaseUrlById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "接入地址不存在或不可访问"));
        Provider provider = requireVisible(current.getProviderId(), user, auth);
        assertWritable(provider, user, auth);
        if (mapper.hasChannelsByBaseUrl(id)) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_BASE_URL_IN_USE, "接入地址仍被通道引用");
        }
        mapper.softDeleteBaseUrl(id);
        runtimeOutboxService.record(provider.getTenantId(), "PROVIDER_BASE_URL", id, "DELETED", null);
    }

    // ==================== 映射与校验 ====================

    private ProviderSummary toSummary(ProviderRow row) {
        return new ProviderSummary(row.getId(), row.getName(), row.getCode(), row.getScopeType(),
                row.getTenantId(), row.getTenantName(), row.getDescription(), row.getStatus(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private ProviderBaseUrlSummary toSummary(ProviderBaseUrl b) {
        return new ProviderBaseUrlSummary(b.getId(), b.getProviderId(), b.getProtocol(),
                b.getOriginalBaseUrl(), b.getNormalizedBaseUrl(), b.getDisplayName(), b.getRemark(),
                b.isEnabled() ? "ENABLED" : "DISABLED", b.getCreatedAt(), b.getUpdatedAt());
    }

    private String normalizeUrl(String baseUrl) {
        try {
            return normalizer.normalize(baseUrl);
        } catch (IllegalArgumentException ex) {
            // normalizer 抛出的是字段级安全文案，转换为上游专用错误码以统一映射
            throw new ProviderException(BusinessErrorCode.UPSTREAM_BASE_URL_INVALID, "接入基础地址不合法");
        }
    }

    private String validateProtocol(String protocol) {
        if ("OPENAI".equals(protocol) || "ANTHROPIC".equals(protocol)) {
            return protocol;
        }
        throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择有效的上游协议");
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请填写有效的名称");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
