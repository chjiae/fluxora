package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.ModelRouteSummary;
import io.fluxora.platform.model.mapper.ModelRouteMapper;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import io.fluxora.platform.runtime.RuntimeOutboxService;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型路由服务。
 * 同一 TenantModel 在同一入站协议下只允许一条未删除路由（部分唯一索引兜底）；
 * 本轮不实现真实协议转换或转发，仅维护控制面配置。
 */
@Service
public class ModelRouteService {

    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("OPENAI", "ANTHROPIC");

    private final ModelRouteMapper routeMapper;
    private final TenantModelService tenantModelService;
    private final UpstreamTenantGuard tenantGuard;
    private final RuntimeOutboxService runtimeOutboxService;

    public ModelRouteService(ModelRouteMapper routeMapper,
                             TenantModelService tenantModelService,
                             UpstreamTenantGuard tenantGuard,
                             RuntimeOutboxService runtimeOutboxService) {
        this.routeMapper = routeMapper;
        this.tenantModelService = tenantModelService;
        this.tenantGuard = tenantGuard;
        this.runtimeOutboxService = runtimeOutboxService;
    }

    @Transactional(readOnly = true)
    public List<ModelRouteSummary> listRoutes(Long tenantModelId, UserAccount user, Authentication auth) {
        tenantModelService.requireVisible(tenantModelId, user, auth);
        return routeMapper.findByTenantModel(tenantModelId);
    }

    @Transactional
    public ModelRouteSummary createRoute(Long tenantModelId, String inboundProtocol, String remark,
                                          UserAccount user, Authentication auth) {
        TenantModel model = tenantModelService.requireVisible(tenantModelId, user, auth);
        tenantGuard.assertWritable(model.getTenantId());

        String protocol = normalizeProtocol(inboundProtocol);
        if (routeMapper.existsActiveByProtocol(tenantModelId, protocol, null) > 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                    "当前模型同一入站协议下已存在路由，无需重复创建");
        }

        ModelRoute entity = new ModelRoute();
        entity.setTenantId(model.getTenantId());
        entity.setTenantModelId(tenantModelId);
        entity.setInboundProtocol(protocol);
        entity.setEnabled(true);
        entity.setRemark(blankToNull(remark));
        entity.setCreatedBy(user.getId());
        entity.setUpdatedBy(user.getId());
        routeMapper.insert(entity);
        runtimeOutboxService.record(model.getTenantId(), "MODEL_ROUTE", entity.getId(), "CREATED", null);
        return routeMapper.findByTenantModel(tenantModelId).stream()
                .filter(s -> s.id().equals(entity.getId()))
                .findFirst()
                .orElseThrow(() -> new ModelException(BusinessErrorCode.INTERNAL_ERROR, "路由创建失败"));
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, UserAccount user, Authentication auth) {
        ModelRoute route = requireRoute(id, user, auth);
        tenantGuard.assertWritable(route.getTenantId());
        routeMapper.setEnabled(id, enabled, user.getId());
        runtimeOutboxService.record(route.getTenantId(), "MODEL_ROUTE", id, enabled ? "ENABLED" : "DISABLED", null);
    }

    @Transactional
    public void updateRemark(Long id, String remark, UserAccount user, Authentication auth) {
        ModelRoute route = requireRoute(id, user, auth);
        tenantGuard.assertWritable(route.getTenantId());
        routeMapper.updateRemark(id, blankToNull(remark), user.getId());
    }

    @Transactional
    public void delete(Long id, UserAccount user, Authentication auth) {
        ModelRoute route = requireRoute(id, user, auth);
        tenantGuard.assertWritable(route.getTenantId());
        // RouteTarget 通过 model_route_id 引用本表；软删 route 后已有 target 不再可见，
        // 因此无需级联硬删 target；后续启用前置会重新校验路由与目标是否存在。
        routeMapper.softDelete(id, user.getId());
        runtimeOutboxService.record(route.getTenantId(), "MODEL_ROUTE", id, "DELETED", null);
    }

    /**
     * 服务层加载路由实体并校验可见性；供 RouteTargetService 复用，避免循环依赖。
     */
    public ModelRoute requireRoute(Long id, UserAccount user, Authentication auth) {
        ModelRoute route = routeMapper.findById(id)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "路由不存在"));
        if (!tenantGuard.isPlatformAdmin(auth) && !route.getTenantId().equals(user.getTenantId())) {
            throw new ModelException(BusinessErrorCode.ACCESS_DENIED, "当前账号没有此操作权限");
        }
        return route;
    }

    static String normalizeProtocol(String protocol) {
        if (protocol == null) throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID, "请填写入站协议");
        String upper = protocol.trim().toUpperCase();
        if (!SUPPORTED_PROTOCOLS.contains(upper)) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                    "入站协议仅支持 OPENAI 或 ANTHROPIC");
        }
        return upper;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
