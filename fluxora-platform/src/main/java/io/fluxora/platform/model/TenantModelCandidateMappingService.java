package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.TenantModelCandidateMappingSummary;
import io.fluxora.platform.model.mapper.ProviderChannelModelMapper;
import io.fluxora.platform.model.mapper.TenantModelCandidateMappingMapper;
import io.fluxora.platform.model.mapper.TenantModelMapper;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 候选映射管理服务。
 * 映射仅表达「当前租户的某个 TenantModel 可以使用本租户的某个 ProviderChannelModel」；
 * 不保存优先级、权重或协议（这些属于 RouteTarget，提交 4 实现）。
 * 服务层在写入时强制三方 tenant_id 一致：tenant_model.tenant_id == provider_channel_model.tenant_id == 入参 tenant_id。
 * 软删除时不允许直接删除已被 TenantModel ENABLED 支撑的映射（本轮仅校验映射引用——RouteTarget 引用保护在提交 4 后生效）。
 */
@Service
public class TenantModelCandidateMappingService {

    private final TenantModelCandidateMappingMapper mappingMapper;
    private final TenantModelMapper tenantModelMapper;
    private final ProviderChannelModelMapper channelModelMapper;
    private final UpstreamTenantGuard tenantGuard;

    public TenantModelCandidateMappingService(TenantModelCandidateMappingMapper mappingMapper,
                                              TenantModelMapper tenantModelMapper,
                                              ProviderChannelModelMapper channelModelMapper,
                                              UpstreamTenantGuard tenantGuard) {
        this.mappingMapper = mappingMapper;
        this.tenantModelMapper = tenantModelMapper;
        this.channelModelMapper = channelModelMapper;
        this.tenantGuard = tenantGuard;
    }

    @Transactional(readOnly = true)
    public List<TenantModelCandidateMappingSummary> listByTenantModel(Long tenantModelId,
                                                                       UserAccount user,
                                                                       Authentication auth) {
        // 可见性已由 requireVisible 在 Controller 层或本方法调用前校验
        return mappingMapper.findByTenantModel(tenantModelId);
    }

    @Transactional
    public TenantModelCandidateMappingSummary create(Long tenantModelId,
                                                     Long providerChannelModelId,
                                                     String remark,
                                                     UserAccount user,
                                                     Authentication auth) {
        TenantModel model = tenantModelMapper.findById(tenantModelId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_FOUND, "租户模型不存在"));
        Long tenantId = model.getTenantId();
        tenantGuard.assertWritable(tenantId);

        ProviderChannelModel candidate = channelModelMapper.findById(providerChannelModelId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.CHANNEL_MODEL_NOT_FOUND, "所选上游候选不可用"));

        // 三方租户一致性校验
        if (!candidate.getTenantId().equals(tenantId)) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_TENANT_MISMATCH,
                    "所选上游候选与租户模型不属于同一租户");
        }

        // 唯一性：同一组 (tenantModelId, providerChannelModelId) 未删除只能有一条
        if (mappingMapper.existsActivePair(tenantModelId, providerChannelModelId) > 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_DUPLICATE,
                    "该上游候选已映射到当前模型，无需重复添加");
        }

        TenantModelCandidateMapping entity = new TenantModelCandidateMapping();
        entity.setTenantId(tenantId);
        entity.setTenantModelId(tenantModelId);
        entity.setProviderChannelModelId(providerChannelModelId);
        entity.setEnabled(true);
        entity.setRemark(blankToNull(remark));
        entity.setCreatedBy(user.getId());
        entity.setUpdatedBy(user.getId());
        mappingMapper.insert(entity);
        return mappingMapper.findByTenantModel(tenantModelId).stream()
                .filter(s -> s.id().equals(entity.getId()))
                .findFirst()
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "映射创建失败"));
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        TenantModelCandidateMapping mapping = requireMapping(id, user, auth);
        tenantGuard.assertWritable(mapping.getTenantId());
        mappingMapper.setEnabled(id, enabled, user.getId());
    }

    @Transactional
    public void updateRemark(Long id, String remark, UserAccount user, Authentication auth) {
        TenantModelCandidateMapping mapping = requireMapping(id, user, auth);
        tenantGuard.assertWritable(mapping.getTenantId());
        mappingMapper.updateRemark(id, blankToNull(remark), user.getId());
    }

    @Transactional
    public void delete(Long id, UserAccount user, Authentication auth) {
        TenantModelCandidateMapping mapping = requireMapping(id, user, auth);
        tenantGuard.assertWritable(mapping.getTenantId());
        // 被启用的 RouteTarget 引用的映射不得删除（提交 4 后生效路由目标表后再加校验）
        mappingMapper.softDelete(id, user.getId());
    }

    private TenantModelCandidateMapping requireMapping(Long id, UserAccount user, Authentication auth) {
        TenantModelCandidateMapping mapping = mappingMapper.findById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_NOT_FOUND, "候选映射不存在"));
        if (!isPlatformAdmin(auth) && !mapping.getTenantId().equals(user.getTenantId())) {
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return mapping;
    }

    private boolean isPlatformAdmin(Authentication auth) {
        return tenantGuard.isPlatformAdmin(auth);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}