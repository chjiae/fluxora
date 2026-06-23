package io.fluxora.platform.model;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import io.fluxora.platform.model.dto.PublicTenantModel;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端公开模型目录接口。
 * 严格按当前登录用户所属租户筛选；绝不接收 tenantId 入参或对外暴露通道、上游、候选、映射、路由信息。
 * 仅返回 publish_status=ENABLED + 有当前有效价格 + 有至少一个可用候选映射的模型。
 */
@RestController
@RequestMapping("/api/models")
public class PublicModelCatalogController {

    private final PublicModelCatalogService service;

    public PublicModelCatalogController(PublicModelCatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_TENANT_MODEL_PUBLIC_READ')")
    public ResponseEntity<ApiResponse<List<PublicTenantModel>>> listPublicModels(
            @AuthenticationPrincipal UserAccount user) {
        return ResponseEntity.ok(ApiResponse.success(service.listForCurrentUser(user)));
    }
}
