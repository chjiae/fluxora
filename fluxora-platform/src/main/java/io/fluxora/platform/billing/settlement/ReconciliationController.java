package io.fluxora.platform.billing.settlement;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.identity.entity.UserAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端待对账接口，权限同时由细粒度权限与服务层平台管理员判断收紧。 */
@RestController
@RequestMapping("/api/admin/billing/reconciliations")
public class ReconciliationController {
    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<ReconciliationPage>> pending(@AuthenticationPrincipal UserAccount user,
                                                                    Authentication auth,
                                                                    @RequestParam(required = false) Long tenantId,
                                                                    @RequestParam(defaultValue = "1") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(service.pending(user, auth, tenantId, page, size)));
    }

    @PostMapping("/{requestId}/settle")
    @PreAuthorize("hasAuthority('PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<BillingSettlementView>> settle(@PathVariable String requestId,
            @RequestBody ReconciliationActionRequest request, @AuthenticationPrincipal UserAccount user,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.confirmSettle(user, auth, requestId, request)));
    }

    @PostMapping("/{requestId}/no-charge")
    @PreAuthorize("hasAuthority('PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<BillingSettlementView>> noCharge(@PathVariable String requestId,
            @RequestBody ReconciliationActionRequest request, @AuthenticationPrincipal UserAccount user,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.confirmNoCharge(user, auth, requestId, request)));
    }
}
