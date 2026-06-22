package io.fluxora.platform.credit;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.credit.dto.*;
import io.fluxora.platform.identity.entity.UserAccount;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CreditController {

    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    // ============ 自身路径（SELF） ============

    @GetMapping("/api/credit/me")
    @PreAuthorize("hasAuthority('PERM_CREDIT_SELF_READ')")
    public ResponseEntity<ApiResponse<CreditAccountView>> myAccount(
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getMyAccount(currentUser)));
    }

    @GetMapping("/api/credit/me/transactions")
    @PreAuthorize("hasAuthority('PERM_CREDIT_SELF_READ')")
    public ResponseEntity<ApiResponse<CreditTransactionPageResponse>> myTransactions(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CreditTransactionQuery q = new CreditTransactionQuery(
                keyword, direction, null, null, from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                creditService.listTransactions(currentUser, CreditService.CreditScope.SELF, q)));
    }

    // ============ 租户路径（TENANT） ============

    @GetMapping("/api/tenant/{tenantId}/credit/accounts/{userId}")
    @PreAuthorize("hasAnyAuthority('PERM_CREDIT_TENANT_READ','PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditAccountView>> accountByUser(
            @PathVariable Long tenantId,
            @PathVariable Long userId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getUserAccount(currentUser, userId)));
    }

    @GetMapping("/api/tenant/{tenantId}/credit/transactions")
    @PreAuthorize("hasAnyAuthority('PERM_CREDIT_TENANT_READ','PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditTransactionPageResponse>> transactionsByTenant(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CreditTransactionQuery q = new CreditTransactionQuery(
                keyword, direction, userId, tenantId, from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                creditService.listTransactions(currentUser, CreditService.CreditScope.TENANT, q)));
    }

    @PostMapping("/api/tenant/{tenantId}/credit/adjust")
    @PreAuthorize("hasAnyAuthority('PERM_CREDIT_TENANT_ADJUST','PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditTransactionView>> adjustInTenant(
            @PathVariable Long tenantId,
            @RequestParam Long userId,
            @RequestBody AdjustCreditRequest req,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(creditService.adjust(currentUser, userId, req)));
    }

    @GetMapping("/api/tenant/{tenantId}/credit/stats")
    @PreAuthorize("hasAnyAuthority('PERM_CREDIT_TENANT_READ','PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditStats>> statsByTenant(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                creditService.getStats(currentUser, CreditService.CreditScope.TENANT, tenantId)));
    }

    // ============ 平台路径（PLATFORM） ============

    @GetMapping("/api/admin/credit/accounts/{userId}")
    @PreAuthorize("hasAuthority('PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditAccountView>> adminAccount(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getUserAccount(currentUser, userId)));
    }

    @GetMapping("/api/admin/credit/transactions")
    @PreAuthorize("hasAuthority('PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditTransactionPageResponse>> adminTransactions(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CreditTransactionQuery q = new CreditTransactionQuery(
                keyword, direction, userId, tenantId, from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                creditService.listTransactions(currentUser, CreditService.CreditScope.PLATFORM, q)));
    }

    @GetMapping("/api/admin/credit/stats")
    @PreAuthorize("hasAuthority('PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<CreditStats>> adminStats(
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                creditService.getStats(currentUser, CreditService.CreditScope.PLATFORM, null)));
    }

    @GetMapping("/api/credit/adjustable-users")
    @PreAuthorize("hasAnyAuthority('PERM_CREDIT_TENANT_ADJUST','PERM_CREDIT_CROSS_TENANT_ADJUST')")
    public ResponseEntity<ApiResponse<List<AdjustableUserOption>>> adjustableUsers(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.success(
                creditService.listAdjustableUsers(currentUser, tenantId, keyword)));
    }
}