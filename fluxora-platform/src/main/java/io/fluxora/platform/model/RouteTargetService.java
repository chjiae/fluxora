package io.fluxora.platform.model;

import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.RouteTargetSummary;
import io.fluxora.platform.model.mapper.MappingResolution;
import io.fluxora.platform.model.mapper.RouteTargetMapper;
import io.fluxora.platform.upstream.security.UpstreamTenantGuard;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 路由目标服务。
 *
 * 写入与启用前必须满足四方租户一致 + 协议兼容：
 *   route.tenant_id == mapping.tenant_id == candidate.tenant_id == channel.tenant_id
 *   route.inbound_protocol == provider_base_url.protocol
 *
 * 优先级与权重只能存在于 RouteTarget；映射本身不承载这两项。
 * 本轮不参与真实调度，仅保存配置。
 */
@Service
public class RouteTargetService {

    private final RouteTargetMapper targetMapper;
    private final ModelRouteService routeService;
    private final UpstreamTenantGuard tenantGuard;

    public RouteTargetService(RouteTargetMapper targetMapper,
                              ModelRouteService routeService,
                              UpstreamTenantGuard tenantGuard) {
        this.targetMapper = targetMapper;
        this.routeService = routeService;
        this.tenantGuard = tenantGuard;
    }

    @Transactional(readOnly = true)
    public List<RouteTargetSummary> listTargets(Long routeId, UserAccount user, Authentication auth) {
        routeService.requireRoute(routeId, user, auth);
        return targetMapper.findByRoute(routeId);
    }

    @Transactional
    public RouteTargetSummary create(Long routeId, Long mappingId, Integer priority, Integer weight,
                                      String remark, UserAccount user, Authentication auth) {
        ModelRoute route = routeService.requireRoute(routeId, user, auth);
        tenantGuard.assertWritable(route.getTenantId());

        // 一次 JOIN 拿回事实快照（映射、候选、通道、协议）；任一资源缺失返回空 Optional
        MappingResolution resolved = targetMapper.resolveMapping(mappingId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_NOT_FOUND,
                        "候选映射不存在或已被删除"));

        // 四方租户一致：route / mapping / candidate 通道 必须同租户
        if (!route.getTenantId().equals(resolved.mappingTenantId())
                || !route.getTenantId().equals(resolved.channelTenantId())) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_TENANT_MISMATCH,
                    "所选映射与路由不属于同一租户");
        }

        // 协议兼容
        if (!route.getInboundProtocol().equalsIgnoreCase(resolved.protocol())) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID,
                    "路由入站协议与候选通道协议不一致");
        }

        // 同一路由下同一映射未删除唯一
        if (targetMapper.existsActivePair(routeId, mappingId) > 0) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_DUPLICATE,
                    "该候选映射已在当前路由中存在，无需重复添加");
        }

        int p = priority == null ? 100 : priority;
        int w = weight == null ? 100 : weight;
        if (p < 0 || p > 100000 || w < 1 || w > 100000) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID, "优先级或权重超出允许范围");
        }

        RouteTarget entity = new RouteTarget();
        entity.setTenantId(route.getTenantId());
        entity.setModelRouteId(routeId);
        entity.setTenantModelCandidateMappingId(mappingId);
        entity.setProviderChannelId(resolved.providerChannelId());
        entity.setUpstreamModelIdSnapshot(resolved.upstreamModelId());
        entity.setEnabled(true);
        entity.setPriority(p);
        entity.setWeight(w);
        entity.setRemark(blankToNull(remark));
        entity.setCreatedBy(user.getId());
        entity.setUpdatedBy(user.getId());
        targetMapper.insert(entity);
        return targetMapper.findByRoute(routeId).stream()
                .filter(s -> s.id().equals(entity.getId()))
                .findFirst()
                .orElseThrow(() -> new ModelException(BusinessErrorCode.INTERNAL_ERROR, "路由目标写入失败"));
    }

    @Transactional
    public RouteTargetSummary update(Long routeId, Long targetId, Boolean enabled, Integer priority,
                                      Integer weight, String remark, UserAccount user, Authentication auth) {
        ModelRoute route = routeService.requireRoute(routeId, user, auth);
        tenantGuard.assertWritable(route.getTenantId());
        RouteTarget current = targetMapper.findById(targetId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "路由目标不存在"));
        if (!current.getModelRouteId().equals(routeId) || !current.getTenantId().equals(route.getTenantId())) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_MAPPING_TENANT_MISMATCH,
                    "路由目标与路由不匹配");
        }
        int p = priority == null ? current.getPriority() : priority;
        int w = weight == null ? current.getWeight() : weight;
        if (p < 0 || p > 100000 || w < 1 || w > 100000) {
            throw new ModelException(BusinessErrorCode.TENANT_MODEL_INVALID, "优先级或权重超出允许范围");
        }
        current.setEnabled(enabled == null ? current.isEnabled() : enabled);
        current.setPriority(p);
        current.setWeight(w);
        current.setRemark(blankToNull(remark));
        current.setUpdatedBy(user.getId());
        targetMapper.update(current);
        return targetMapper.findByRoute(routeId).stream()
                .filter(s -> s.id().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "路由目标已不存在"));
    }

    @Transactional
    public void delete(Long routeId, Long targetId, UserAccount user, Authentication auth) {
        ModelRoute route = routeService.requireRoute(routeId, user, auth);
        tenantGuard.assertWritable(route.getTenantId());
        RouteTarget current = targetMapper.findById(targetId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "路由目标不存在"));
        if (!current.getModelRouteId().equals(routeId)) {
            throw new ModelException(BusinessErrorCode.RESOURCE_NOT_FOUND, "路由目标不属于当前路由");
        }
        targetMapper.softDelete(targetId, user.getId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
