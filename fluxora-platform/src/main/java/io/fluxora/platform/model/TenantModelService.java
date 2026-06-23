package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.TenantModelStats;
import io.fluxora.platform.model.dto.TenantModelSummary;
import io.fluxora.platform.model.mapper.ModelRouteMapper;
import io.fluxora.platform.model.mapper.ProviderChannelModelMapper;
import io.fluxora.platform.model.mapper.TenantModelCandidateMappingMapper;
import io.fluxora.platform.model.mapper.TenantModelMapper;
import io.fluxora.platform.model.mapper.TenantModelPriceMapper;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户模型管理服务。
 * V10 后不存在任何全局模型依赖；模型编码只要求同租户内唯一，跨租户完全隔离。
 * 平台管理员必须显式指定目标租户（requestedTenantId）才可执行写操作；
 * 租户管理员强行使用当前租户，不会因前端传入其他 tenantId 而越权。
 */
@Service
public class TenantModelService {

    private final TenantModelMapper tenantModelMapper;
    private final TenantModelCandidateMappingMapper mappingMapper;
    private final ProviderChannelModelMapper channelModelMapper;
    private final TenantModelPriceMapper priceMapper;
    private final ModelRouteMapper routeMapper;
    private final UpstreamTenantGuard tenantGuard;

    public TenantModelService(TenantModelMapper tenantModelMapper,
                              TenantModelCandidateMappingMapper mappingMapper,
                              ProviderChannelModelMapper channelModelMapper,
                              TenantModelPriceMapper priceMapper,
                              ModelRouteMapper routeMapper,
                              UpstreamTenantGuard tenantGuard) {
        this.tenantModelMapper = tenantModelMapper;
        this.mappingMapper = mappingMapper;
        this.channelModelMapper = channelModelMapper;
        this.priceMapper = priceMapper;
        this.routeMapper = routeMapper;
        this.tenantGuard = tenantGuard;
    }

    public boolean isPlatformAdmin(Authentication auth) {
        return tenantGuard.isPlatformAdmin(auth);
    }

    // ========== 租户解析（与 ProviderChannelService 一致） ==========

    /** 平台管理员必须明确指定目标租户；租户管理员不得通过参数越权。 */
    public Long resolveTenant(Long requestedTenantId, UserAccount user, Authentication auth) {
        if (isPlatformAdmin(auth)) {
            if (requestedTenantId == null) {
                throw new ModelException(BusinessErrorCode.VALIDATION_ERROR, "请选择目标租户");
            }
            return requestedTenantId;
        }
        if (user.getTenantId() == null
                || (requestedTenantId != null && !requestedTenantId.equals(user.getTenantId()))) {
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return user.getTenantId();
    }

    /** 列表租户解析：平台管理员可为空（全部）；租户管理员强制本租户。 */
    private Long resolveListTenant(Long requestedTenantId, UserAccount user, boolean platform) {
        if (platform) return requestedTenantId;
        if (user.getTenantId() == null) {
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return user.getTenantId();
    }

    // ========== CRUD ==========

    @Transactional(readOnly = true)
    public UpstreamPage<TenantModelSummary> listModels(UserAccount user, Authentication auth,
                                                       Long tenantId, String keyword, String status,
                                                       int page, int size) {
        boolean platform = isPlatformAdmin(auth);
        Long resolved = resolveListTenant(tenantId, user, platform);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<TenantModelSummary> items = tenantModelMapper.findPage(resolved, platform,
                blankToNull(keyword), blankToNull(status),
                (safePage - 1) * safeSize, safeSize);
        long total = tenantModelMapper.countPage(resolved, platform,
                blankToNull(keyword), blankToNull(status));
        return new UpstreamPage<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public TenantModelStats stats(UserAccount user, Authentication auth, Long tenantId) {
        boolean platform = isPlatformAdmin(auth);
        Long resolved = resolveListTenant(tenantId, user, platform);
        return tenantModelMapper.stats(resolved, platform);
    }

    @Transactional(readOnly = true)
    public TenantModelSummary detail(Long id, UserAccount user, Authentication auth) {
        TenantModel model = requireVisible(id, user, auth);
        return tenantModelMapper.findSummaryById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_FOUND, "租户模型不存在"));
    }

    @Transactional
    public TenantModelSummary create(TenantModel entity, Long requestedTenantId,
                                     UserAccount user, Authentication auth) {
        Long tenantId = resolveTenant(requestedTenantId, user, auth);
        tenantGuard.assertWritable(tenantId);
        entity.setTenantId(tenantId);
        validateBasics(entity);
        assertCodeUnique(tenantId, entity.getModelCode(), null);
        entity.setPublishStatus("DRAFT");
        entity.setEnabled(false);
        entity.setCreatedBy(user.getId());
        entity.setUpdatedBy(user.getId());
        tenantModelMapper.insert(entity);
        return detail(entity.getId(), user, auth);
    }

    @Transactional
    public TenantModelSummary update(Long id, TenantModel patch,
                                     UserAccount user, Authentication auth) {
        TenantModel current = requireVisible(id, user, auth);
        Long tenantId = current.getTenantId();
        tenantGuard.assertWritable(tenantId);
        validateBasics(patch);
        assertCodeUnique(tenantId, patch.getModelCode(), id);
        current.setModelCode(patch.getModelCode());
        current.setDisplayName(patch.getDisplayName());
        current.setDescription(patch.getDescription());
        current.setSupportsStreaming(patch.isSupportsStreaming());
        current.setSupportsToolCalling(patch.isSupportsToolCalling());
        current.setSupportsVision(patch.isSupportsVision());
        current.setSupportsCache(patch.isSupportsCache());
        current.setUpdatedBy(user.getId());
        tenantModelMapper.updateBasics(current);
        return detail(id, user, auth);
    }

    @Transactional
    public void enable(Long id, UserAccount user, Authentication auth) {
        TenantModel current = requireVisible(id, user, auth);
        Long tenantId = current.getTenantId();
        tenantGuard.assertWritable(tenantId);
        // 校验启用前置条件
        assertEnableable(current);
        tenantModelMapper.setPublishStatus(id, "ENABLED", true, user.getId());
    }

    @Transactional
    public void disable(Long id, UserAccount user, Authentication auth) {
        TenantModel current = requireVisible(id, user, auth);
        tenantGuard.assertWritable(current.getTenantId());
        tenantModelMapper.setPublishStatus(id, "DISABLED", false, user.getId());
    }

    @Transactional
    public void delete(Long id, UserAccount user, Authentication auth) {
        TenantModel current = requireVisible(id, user, auth);
        tenantGuard.assertWritable(current.getTenantId());
        tenantModelMapper.softDelete(id, user.getId());
    }

    // ========== 内部校验 ==========

    public TenantModel requireVisible(Long id, UserAccount user, Authentication auth) {
        TenantModel model = tenantModelMapper.findById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_FOUND, "租户模型不存在"));
        if (!isPlatformAdmin(auth) && !model.getTenantId().equals(user.getTenantId())) {
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return model;
    }

    private void validateBasics(TenantModel m) {
        if (m.getModelCode() == null || m.getModelCode().isBlank() || m.getModelCode().length() > 128
                || m.getDisplayName() == null || m.getDisplayName().isBlank() || m.getDisplayName().length() > 256) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID, "模型编码或展示名不合法");
        }
    }

    private void assertCodeUnique(Long tenantId, String code, Long excludeId) {
        if (tenantModelMapper.existsActiveByCode(tenantId, code, excludeId) > 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_CODE_DUPLICATE,
                    "当前租户已存在相同模型编码");
        }
    }

    /**
     * 启用前置条件：
     * 1. 存在至少一个有效候选映射（映射启用 + 候选启用 + 通道启用 + 全部未删除）；
     * 2. 存在当前有效价格（tenant_model_price.expired_at IS NULL）；
     * 3. 存在至少一个未删除路由（model_route）；
     * 4. 租户声明能力必须被至少一个有效候选支撑；
     * 5. 候选的 enabled 状态与通道 enabled 状态必须为 TRUE；
     * 6. 路由数量 > 0 且至少一条路由有 RouteTarget（本轮仅检查路由存在，RouteTarget 在提交 4 后强制）。
     * 本轮未实现满路由 / 满 RouteTarget 校验——提交 4 后补齐。
     */
    private void assertEnableable(TenantModel model) {
        Long id = model.getId();
        // 条件 1：至少存在一个有效映射
        long mappingCount = mappingMapper.countActiveByTenantModel(id);
        if (mappingCount == 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_ENABLEABLE,
                    "请至少先完成一个有效的上游候选映射");
        }
        // 条件 2：必须存在当前有效价格
        if (priceMapper.findCurrent(id).isEmpty()) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_ENABLEABLE,
                    "请先配置有效价格后再发布模型");
        }
        // 条件 3：至少存在一条未删除路由
        if (routeMapper.countActiveByTenantModel(id) == 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_ENABLEABLE,
                    "请至少配置一个可用上游路由后再发布模型");
        }
        // 条件 3b：至少存在一个未删除 RouteTarget；
        // 路由不带 RouteTarget 意味着请求无可用上游候选，与「无映射」语义等价
        if (routeMapper.countActiveTargetsByTenantModel(id) == 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_ENABLEABLE,
                    "请至少配置一个有效路由目标后再发布模型");
        }
        // 条件 4：能力支撑校验——从有效候选集合中逐一比对
        List<ProviderChannelModel> candidates = mappingMapper.findActiveSupportingCandidates(id);
        if (candidates.isEmpty()) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_NOT_ENABLEABLE,
                    "当前映射中无可用上游候选（候选或所属通道已停用）");
        }
        // 如果任何能力不足（租户声明了但无候选支撑），拒启用
        if (model.isSupportsStreaming() && candidates.stream().noneMatch(ProviderChannelModel::isSupportsStreaming)) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_CAPABILITY_UNSUPPORTED,
                    "流式能力不被任何有效上游候选支撑");
        }
        if (model.isSupportsToolCalling() && candidates.stream().noneMatch(ProviderChannelModel::isSupportsToolCalling)) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_CAPABILITY_UNSUPPORTED,
                    "工具调用能力不被任何有效上游候选支撑");
        }
        if (model.isSupportsVision() && candidates.stream().noneMatch(ProviderChannelModel::isSupportsVision)) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_CAPABILITY_UNSUPPORTED,
                    "视觉能力不被任何有效上游候选支撑");
        }
        if (model.isSupportsCache() && candidates.stream().noneMatch(ProviderChannelModel::isSupportsCache)) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_CAPABILITY_UNSUPPORTED,
                    "缓存能力不被任何有效上游候选支撑");
        }
    }

    // ========== 工具 ==========

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}