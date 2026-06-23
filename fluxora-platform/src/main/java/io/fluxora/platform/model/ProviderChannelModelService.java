package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.ProviderChannelModelSummary;
import io.fluxora.platform.model.mapper.ProviderChannelModelMapper;
import io.fluxora.platform.upstream.channel.ProviderChannel;
import io.fluxora.platform.upstream.channel.ProviderChannelMapper;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import io.fluxora.platform.runtime.RuntimeOutboxService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上游候选管理服务。
 * 候选必须属于具体租户的具体通道；候选不再映射到任何全局模型（无 platform_model_id）。
 * 禁用/删除候选前需校验是否仍被租户模型的候选映射引用，如果被引用则拦截。
 * 候选的能力字段用于校验 TenantModel 启用前的能力一致性。
 */
@Service
public class ProviderChannelModelService {

    private final ProviderChannelModelMapper mapper;
    private final ProviderChannelMapper channelMapper;
    private final UpstreamTenantGuard tenantGuard;
    private final RuntimeOutboxService runtimeOutboxService;

    public ProviderChannelModelService(ProviderChannelModelMapper mapper,
                                       ProviderChannelMapper channelMapper,
                                       UpstreamTenantGuard tenantGuard,
                                       RuntimeOutboxService runtimeOutboxService) {
        this.mapper = mapper;
        this.channelMapper = channelMapper;
        this.tenantGuard = tenantGuard;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    public boolean isPlatformAdmin(Authentication auth) {
        return tenantGuard.isPlatformAdmin(auth);
    }

    @Transactional(readOnly = true)
    public List<ProviderChannelModelSummary> listByChannel(Long channelId) {
        // 候选的可见性以通道可见性为前提——通道不存在则候选也不存在
        return mapper.findByChannel(channelId);
    }

    @Transactional
    public ProviderChannelModelSummary create(Long channelId, ProviderChannelModel entity,
                                              UserAccount user, Authentication auth) {
        ProviderChannel channel = channelMapper.findById(channelId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "上游通道不存在"));
        tenantGuard.assertWritable(channel.getTenantId());
        Long tenantId = channel.getTenantId();
        entity.setTenantId(tenantId);
        entity.setProviderChannelId(channelId);
        if (entity.getUpstreamModelId() == null || entity.getUpstreamModelId().isBlank()) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID, "上游模型标识不能为空");
        }
        if (entity.getUpstreamDisplayName() == null || entity.getUpstreamDisplayName().isBlank()) {
            entity.setUpstreamDisplayName(entity.getUpstreamModelId());
        }
        // 同通道同 ID 未删除唯一
        if (mapper.existsActiveByUpstreamId(channelId, entity.getUpstreamModelId(), null) > 0) {
            throw new ModelException(BusinessErrorCode.CHANNEL_MODEL_DUPLICATE, "当前通道下已存在相同上游模型标识");
        }
        entity.setSourceType("MANUAL");
        entity.setEnabled(entity.isEnabled());
        entity.setCreatedBy(user.getId());
        entity.setUpdatedBy(user.getId());
        mapper.insert(entity);
        runtimeOutboxService.record(tenantId, "PROVIDER_CHANNEL_MODEL", entity.getId(), "CREATED", null);
        return mapper.findByChannel(channelId).stream()
                .filter(s -> s.id().equals(entity.getId()))
                .findFirst()
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "候选创建失败"));
    }

    @Transactional
    public ProviderChannelModelSummary update(Long id, ProviderChannelModel patch,
                                              UserAccount user, Authentication auth) {
        ProviderChannelModel current = mapper.findById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.CHANNEL_MODEL_NOT_FOUND, "上游候选不存在"));
        tenantGuard.assertWritable(current.getTenantId());
        if ((patch.getUpstreamModelId() != null && !patch.getUpstreamModelId().equals(current.getUpstreamModelId()))
                || (patch.getUpstreamDisplayName() != null && !patch.getUpstreamDisplayName().isBlank())) {
            if (mapper.existsActiveByUpstreamId(current.getProviderChannelId(),
                    patch.getUpstreamModelId(), id) > 0) {
                throw new ModelException(BusinessErrorCode.CHANNEL_MODEL_DUPLICATE, "当前通道下已存在相同上游模型标识");
            }
        }
        if (patch.getUpstreamModelId() != null) current.setUpstreamModelId(patch.getUpstreamModelId());
        if (patch.getUpstreamDisplayName() != null) current.setUpstreamDisplayName(patch.getUpstreamDisplayName());
        current.setSupportsStreaming(patch.isSupportsStreaming());
        current.setSupportsToolCalling(patch.isSupportsToolCalling());
        current.setSupportsVision(patch.isSupportsVision());
        current.setSupportsCache(patch.isSupportsCache());
        current.setUpdatedBy(user.getId());
        mapper.updateBasics(current);
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER_CHANNEL_MODEL", id, "UPDATED", null);
        return mapper.findByChannel(current.getProviderChannelId()).stream()
                .filter(s -> s.id().equals(id))
                .findFirst().orElseThrow(() -> new ModelException(BusinessErrorCode.CHANNEL_MODEL_NOT_FOUND, "候选已不存在"));
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        ProviderChannelModel current = mapper.findById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.CHANNEL_MODEL_NOT_FOUND, "上游候选不存在"));
        tenantGuard.assertWritable(current.getTenantId());
        mapper.setEnabled(id, enabled, user.getId());
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER_CHANNEL_MODEL", id,
                enabled ? "ENABLED" : "DISABLED", null);
    }

    @Transactional
    public void delete(Long id, UserAccount user, Authentication auth) {
        ProviderChannelModel current = mapper.findById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.CHANNEL_MODEL_NOT_FOUND, "上游候选不存在"));
        tenantGuard.assertWritable(current.getTenantId());
        long refCount = mapper.countActiveMappings(id);
        if (refCount > 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_IN_USE,
                    "当前候选仍被租户模型映射引用，无法删除");
        }
        mapper.softDelete(id, user.getId());
        runtimeOutboxService.record(current.getTenantId(), "PROVIDER_CHANNEL_MODEL", id, "DELETED", null);
    }
}
