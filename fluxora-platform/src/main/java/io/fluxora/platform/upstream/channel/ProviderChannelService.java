package io.fluxora.platform.upstream.channel;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.upstream.channel.dto.ProviderChannelStats;
import io.fluxora.platform.upstream.channel.dto.ProviderChannelSummary;
import io.fluxora.platform.upstream.channel.mapper.ProviderChannelRow;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import io.fluxora.platform.upstream.provider.Provider;
import io.fluxora.platform.upstream.provider.ProviderBaseUrl;
import io.fluxora.platform.upstream.provider.ProviderException;
import io.fluxora.platform.upstream.provider.ProviderMapper;
import io.fluxora.platform.upstream.provider.ProviderService;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import io.fluxora.platform.runtime.RuntimeOutboxService;

/**
 * 上游通道服务。
 *
 * 租户归属、引用地址可见性与运行参数边界统一在此层强制：
 *   - 租户管理员只能管理本租户通道，客户端 tenantId 永不被信任；
 *   - 通道引用的 BaseUrl 必须来自平台共享 Provider 或当前租户私有 Provider，且关联资源启用；
 *   - 私有 Provider 的 BaseUrl 不得跨租户引用。
 */
@Service
public class ProviderChannelService {

    private final ProviderChannelMapper channelMapper;
    private final ProviderMapper providerMapper;
    private final ProviderService providerService;
    private final UpstreamTenantGuard tenantGuard;
    private final RuntimeOutboxService runtimeOutboxService;

    public ProviderChannelService(ProviderChannelMapper channelMapper, ProviderMapper providerMapper,
            ProviderService providerService, UpstreamTenantGuard tenantGuard,
            RuntimeOutboxService runtimeOutboxService) {
        this.channelMapper = channelMapper;
        this.providerMapper = providerMapper;
        this.providerService = providerService;
        this.tenantGuard = tenantGuard;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    public boolean isPlatformAdmin(Authentication auth) {
        return tenantGuard.isPlatformAdmin(auth);
    }

    @Transactional(readOnly = true)
    public UpstreamPage<ProviderChannelSummary> listChannels(UserAccount user, Authentication auth,
            Long tenantId, String keyword, Long providerId, String protocol, Boolean enabled, int page, int size) {
        boolean platform = isPlatformAdmin(auth);
        Long resolved = resolveListTenant(tenantId, user, platform);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<ProviderChannelRow> rows = channelMapper.findRows(resolved, platform,
                blankToNull(keyword), providerId, blankToNull(protocol), enabled, (safePage - 1) * safeSize, safeSize);
        long total = channelMapper.countRows(resolved, platform,
                blankToNull(keyword), providerId, blankToNull(protocol), enabled);
        return new UpstreamPage<>(rows.stream().map(this::toSummary).toList(), total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ProviderChannelSummary channelDetail(Long id, UserAccount user, Authentication auth) {
        return channelMapper.findRowById(id, user.getTenantId(), isPlatformAdmin(auth))
                .map(this::toSummary)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游通道不存在或不可访问"));
    }

    @Transactional(readOnly = true)
    public ProviderChannelStats channelStats(UserAccount user, Authentication auth, Long tenantId) {
        boolean platform = isPlatformAdmin(auth);
        Long resolved = resolveListTenant(tenantId, user, platform);
        return channelMapper.stats(resolved, platform);
    }

    @Transactional
    public ProviderChannelSummary create(ProviderChannel channel, Long requestedTenantId, UserAccount user, Authentication auth) {
        Long tenantId = resolveTenant(requestedTenantId, user, auth);
        tenantGuard.assertWritable(tenantId);
        channel.setTenantId(tenantId);
        validate(channel);
        assertBaseUrlUsable(channel.getProviderBaseUrlId(), tenantId, user, auth);
        channelMapper.insert(channel);
        runtimeOutboxService.record(tenantId, "PROVIDER_CHANNEL", channel.getId(), "CREATED", null);
        return channelDetail(channel.getId(), user, auth);
    }

    @Transactional
    public ProviderChannelSummary update(Long id, ProviderChannel patch, UserAccount user, Authentication auth) {
        ProviderChannel current = requireVisible(id, user, auth);
        tenantGuard.assertWritable(current.getTenantId());
        validate(patch);
        assertBaseUrlUsable(patch.getProviderBaseUrlId(), current.getTenantId(), user, auth);
        current.setProviderBaseUrlId(patch.getProviderBaseUrlId());
        current.setName(patch.getName());
        current.setPriority(patch.getPriority());
        current.setWeight(patch.getWeight());
        current.setConnectTimeoutMs(patch.getConnectTimeoutMs());
        current.setReadTimeoutMs(patch.getReadTimeoutMs());
        current.setRemark(patch.getRemark());
        channelMapper.update(current);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER_CHANNEL", id, "UPDATED", null);
        return channelDetail(id, user, auth);
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        ProviderChannel current = requireVisible(id, user, auth);
        tenantGuard.assertWritable(current.getTenantId());
        channelMapper.setEnabled(id, enabled);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER_CHANNEL", id,
                enabled ? "ENABLED" : "DISABLED", null);
    }

    @Transactional
    public void delete(Long id, UserAccount user, Authentication auth) {
        ProviderChannel current = requireVisible(id, user, auth);
        tenantGuard.assertWritable(current.getTenantId());
        if (channelMapper.hasCredentials(id)) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_CHANNEL_IN_USE, "通道仍被凭证引用");
        }
        channelMapper.softDelete(id);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER_CHANNEL", id, "DELETED", null);
    }

    /**
     * 校验当前用户对通道的可见性，返回通道实体供凭证服务复用。
     * 平台管理员通过；租户管理员需与通道同租户。
     */
    public ProviderChannel requireVisible(Long id, UserAccount user, Authentication auth) {
        ProviderChannel channel = channelMapper.findById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游通道不存在或不可访问"));
        if (!isPlatformAdmin(auth) && !channel.getTenantId().equals(user.getTenantId())) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return channel;
    }

    /** 平台管理员必须明确指定目标租户；租户管理员不得通过参数越权。 */
    public Long resolveTenant(Long requestedTenantId, UserAccount user, Authentication auth) {
        if (isPlatformAdmin(auth)) {
            if (requestedTenantId == null) {
                throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "请选择目标租户");
            }
            return requestedTenantId;
        }
        if (user.getTenantId() == null
                || (requestedTenantId != null && !requestedTenantId.equals(user.getTenantId()))) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return user.getTenantId();
    }

    /** 列表租户解析：平台管理员可按 tenantId 过滤（不传则全部）；租户管理员强制本租户。 */
    private Long resolveListTenant(Long requestedTenantId, UserAccount user, boolean platform) {
        if (platform) {
            return requestedTenantId;
        }
        if (user.getTenantId() == null) {
            throw new ProviderException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return user.getTenantId();
    }

    private void assertBaseUrlUsable(Long id, Long tenantId, UserAccount user, Authentication auth) {
        ProviderBaseUrl baseUrl = providerMapper.findBaseUrlById(id)
                .orElseThrow(() -> new ProviderException(BusinessErrorCode.RESOURCE_NOT_FOUND, "接入地址不存在或不可访问"));
        Provider provider = providerService.requireVisible(baseUrl.getProviderId(), user, auth);
        // 平台管理员为目标租户配置通道时，同样必须校验私有厂商与目标租户一致，不能借管理员权限串租户。
        if (!baseUrl.isEnabled() || !provider.isEnabled()
                || ("TENANT_PRIVATE".equals(provider.getScopeType()) && !tenantId.equals(provider.getTenantId()))) {
            throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR, "接入地址当前不可用于创建通道");
        }
    }

    private void validate(ProviderChannel c) {
        if (c.getName() == null || c.getName().isBlank() || c.getName().length() > 128
                || c.getPriority() < 0 || c.getPriority() > 100000
                || c.getWeight() < 1 || c.getWeight() > 100000
                || c.getConnectTimeoutMs() < 100 || c.getConnectTimeoutMs() > 120000
                || c.getReadTimeoutMs() < 100 || c.getReadTimeoutMs() > 600000) {
            throw new ProviderException(BusinessErrorCode.UPSTREAM_CHANNEL_PARAM_INVALID, "通道运行参数不合法");
        }
    }

    private ProviderChannelSummary toSummary(ProviderChannelRow r) {
        return new ProviderChannelSummary(r.getId(), r.getTenantId(), r.getTenantName(),
                r.getProviderId(), r.getProviderName(), r.getProviderBaseUrlId(), r.getProtocol(),
                r.getNormalizedBaseUrl(), r.getName(), r.getStatus(), r.getPriority(), r.getWeight(),
                r.getConnectTimeoutMs(), r.getReadTimeoutMs(), r.getRemark(), r.getCredentialCount(),
                r.getCreatedAt(), r.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
