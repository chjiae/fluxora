package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.billing.DecimalStringDeserializer;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.ModelRouteSummary;
import io.fluxora.platform.model.dto.TenantModelCandidateMappingSummary;
import io.fluxora.platform.model.dto.TenantModelPriceView;
import io.fluxora.platform.model.dto.TenantModelStats;
import io.fluxora.platform.model.dto.TenantModelSummary;
import io.fluxora.platform.upstream.dto.UpstreamPage;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 租户模型管理与候选映射接口。
 * 所有写操作强制服务层解析目标租户：平台管理员必须显式 tenantId；租户管理员忽略客户端入参，使用 JWT 当前租户。
 * Controller 只做协议转换，不参与权限解析、租户判定或业务校验。
 */
@RestController
@RequestMapping("/api/tenant-models")
public class TenantModelController {

    private final TenantModelService tenantModelService;
    private final TenantModelCandidateMappingService mappingService;
    private final TenantModelPriceService priceService;
    private final ModelRouteService routeService;

    public TenantModelController(TenantModelService tenantModelService,
                                 TenantModelCandidateMappingService mappingService,
                                 TenantModelPriceService priceService,
                                 ModelRouteService routeService) {
        this.tenantModelService = tenantModelService;
        this.mappingService = mappingService;
        this.priceService = priceService;
        this.routeService = routeService;
    }

    // ========== 列表 / 详情 / 指标 ==========

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<UpstreamPage<TenantModelSummary>>> list(
            @AuthenticationPrincipal UserAccount user, Authentication auth,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                tenantModelService.listModels(user, auth, tenantId, keyword, status, page, size)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<TenantModelStats>> stats(
            @AuthenticationPrincipal UserAccount user, Authentication auth,
            @RequestParam(required = false) Long tenantId) {
        return ResponseEntity.ok(ApiResponse.success(
                tenantModelService.stats(user, auth, tenantId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<TenantModelSummary>> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(tenantModelService.detail(id, user, auth)));
    }

    // ========== 创建 / 编辑 / 启停 / 删除 ==========

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<TenantModelSummary>> create(
            @RequestBody TenantModelRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                tenantModelService.create(toEntity(req), req.tenantId(), user, auth)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<TenantModelSummary>> update(
            @PathVariable Long id, @RequestBody TenantModelRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                tenantModelService.update(id, toEntity(req), user, auth)));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> enable(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        tenantModelService.enable(id, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> disable(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        tenantModelService.disable(id, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        tenantModelService.delete(id, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 候选映射子资源 ==========

    @GetMapping("/{tenantModelId}/candidate-mappings")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<List<TenantModelCandidateMappingSummary>>> listMappings(
            @PathVariable Long tenantModelId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        // 先校验模型可见性
        tenantModelService.requireVisible(tenantModelId, user, auth);
        return ResponseEntity.ok(ApiResponse.success(
                mappingService.listByTenantModel(tenantModelId, user, auth)));
    }

    @PostMapping("/{tenantModelId}/candidate-mappings")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<TenantModelCandidateMappingSummary>> createMapping(
            @PathVariable Long tenantModelId,
            @RequestBody MappingRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        tenantModelService.requireVisible(tenantModelId, user, auth);
        return ResponseEntity.ok(ApiResponse.success(mappingService.create(
                tenantModelId, req.providerChannelModelId(), req.remark(), user, auth)));
    }

    @PutMapping("/{tenantModelId}/candidate-mappings/{mappingId}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> updateMapping(
            @PathVariable Long tenantModelId,
            @PathVariable Long mappingId,
            @RequestBody MappingUpdateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        tenantModelService.requireVisible(tenantModelId, user, auth);
        if (req.enabled() != null) {
            mappingService.setEnabled(mappingId, req.enabled(), user, auth);
        }
        if (req.remark() != null) {
            mappingService.updateRemark(mappingId, req.remark(), user, auth);
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{tenantModelId}/candidate-mappings/{mappingId}")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> deleteMapping(
            @PathVariable Long tenantModelId,
            @PathVariable Long mappingId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        tenantModelService.requireVisible(tenantModelId, user, auth);
        mappingService.delete(mappingId, user, auth);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 请求体（record） ==========

    private TenantModel toEntity(TenantModelRequest r) {
        TenantModel m = new TenantModel();
        m.setModelCode(r.modelCode());
        m.setDisplayName(r.displayName());
        m.setDescription(r.description());
        m.setSupportsStreaming(r.supportsStreaming() != null && r.supportsStreaming());
        m.setSupportsToolCalling(r.supportsToolCalling() != null && r.supportsToolCalling());
        m.setSupportsVision(r.supportsVision() != null && r.supportsVision());
        m.setSupportsCache(r.supportsCache() != null && r.supportsCache());
        return m;
    }

    // ========== 价格子资源 ==========

    @GetMapping("/{tenantModelId}/prices/current")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<TenantModelPriceView>> currentPrice(
            @PathVariable Long tenantModelId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                priceService.currentPrice(tenantModelId, user, auth).orElse(null)));
    }

    @GetMapping("/{tenantModelId}/prices")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<List<TenantModelPriceView>>> priceHistory(
            @PathVariable Long tenantModelId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                priceService.priceHistory(tenantModelId, user, auth)));
    }

    @PostMapping("/{tenantModelId}/prices")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<TenantModelPriceView>> publishPrice(
            @PathVariable Long tenantModelId,
            @RequestBody PricePublishRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(priceService.publishPrice(
                tenantModelId,
                req.inputPricePerMillion(),
                req.outputPricePerMillion(),
                req.cacheWritePricePerMillion(),
                req.cacheReadPricePerMillion(),
                user, auth)));
    }

    public record TenantModelRequest(
            /** 平台管理员必须显式传 tenantId；租户管理员忽略此入参，强制使用 JWT 当前租户 */
            Long tenantId,
            String modelCode,
            String displayName,
            String description,
            Boolean supportsStreaming,
            Boolean supportsToolCalling,
            Boolean supportsVision,
            Boolean supportsCache
    ) {
    }

    public record MappingRequest(Long providerChannelModelId, String remark) {
    }

    public record MappingUpdateRequest(Boolean enabled, String remark) {
    }

    /**
     * 价格发布请求体：所有金额字段必须为十进制字符串；
     * 由 DecimalStringDeserializer 全局拒绝 JSON Number / 科学计数法 / 布尔。
     */
    public record PricePublishRequest(
            @JsonDeserialize(using = DecimalStringDeserializer.class) String inputPricePerMillion,
            @JsonDeserialize(using = DecimalStringDeserializer.class) String outputPricePerMillion,
            @JsonDeserialize(using = DecimalStringDeserializer.class) String cacheWritePricePerMillion,
            @JsonDeserialize(using = DecimalStringDeserializer.class) String cacheReadPricePerMillion
    ) {
    }

    // ========== 路由子资源（列表 / 创建） ==========

    @GetMapping("/{tenantModelId}/routes")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_READ')")
    public ResponseEntity<ApiResponse<List<ModelRouteSummary>>> listRoutes(
            @PathVariable Long tenantModelId,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                routeService.listRoutes(tenantModelId, user, auth)));
    }

    @PostMapping("/{tenantModelId}/routes")
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_MANAGE')")
    public ResponseEntity<ApiResponse<ModelRouteSummary>> createRoute(
            @PathVariable Long tenantModelId,
            @RequestBody RouteCreateRequest req,
            @AuthenticationPrincipal UserAccount user, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                routeService.createRoute(tenantModelId, req.inboundProtocol(), req.remark(), user, auth)));
    }

    public record RouteCreateRequest(String inboundProtocol, String remark) {
    }
}