package io.fluxora.platform.card;

import io.fluxora.common.response.ApiResponse;
import io.fluxora.platform.card.dto.*;
import io.fluxora.platform.identity.entity.UserAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 卡密 REST 控制器。
 *
 * 三组路由：
 *   - /api/cards/redeem                  普通用户核销
 *   - /api/cards/{id}/enable|disable     租户/平台管理员对单张卡密变更状态
 *   - /api/tenant/{tenantId}/cards/*     租户路径（租户管理员 / 平台管理员）
 *   - /api/admin/cards/*                 跨租户（仅平台管理员）
 *
 * 所有业务规则与跨租户校验由 {@link CardService} 承载。
 */
@RestController
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    // ============ 核销（普通用户）============

    @PostMapping("/api/cards/redeem")
    @PreAuthorize("hasAuthority('PERM_CARD_SELF_REDEEM')")
    public ResponseEntity<ApiResponse<RedeemedResponse>> redeem(
            @RequestBody RedeemRequest req,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                cardService.redeem(currentUser, req == null ? null : req.code())));
    }

    // ============ 租户路径（租户管理员 / 平台管理员）============

    @PostMapping("/api/tenant/{tenantId}/cards/batches")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CreatedBatchResponse>> createBatch(
            @PathVariable Long tenantId,
            @RequestBody CreateBatchRequest req,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                cardService.createBatch(currentUser, tenantId, req)));
    }

    @GetMapping("/api/tenant/{tenantId}/cards/batches")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<BatchPageResponse>> listBatches(
            @PathVariable Long tenantId,
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String denomination,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        BatchQuery q = new BatchQuery(keyword, status, denomination, tenantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(cardService.listBatches(currentUser, q)));
    }

    @GetMapping("/api/tenant/{tenantId}/cards/batches/{batchId}")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CardBatchSummary>> batchDetail(
            @PathVariable Long tenantId,
            @PathVariable Long batchId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(cardService.getBatchDetail(currentUser, batchId)));
    }

    @PutMapping("/api/tenant/{tenantId}/cards/batches/{batchId}/enable")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CardBatchSummary>> enableBatch(
            @PathVariable Long tenantId, @PathVariable Long batchId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                cardService.setBatchEnabled(currentUser, batchId, true)));
    }

    @PutMapping("/api/tenant/{tenantId}/cards/batches/{batchId}/disable")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CardBatchSummary>> disableBatch(
            @PathVariable Long tenantId, @PathVariable Long batchId,
            @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                cardService.setBatchEnabled(currentUser, batchId, false)));
    }

    @GetMapping("/api/tenant/{tenantId}/cards/batches/{batchId}/cards")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CardPageResponse>> listCardsInBatch(
            @PathVariable Long tenantId, @PathVariable Long batchId,
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String prefixKeyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CardQuery q = new CardQuery(batchId, prefixKeyword, null, status, null, page, size);
        return ResponseEntity.ok(ApiResponse.success(cardService.listCards(currentUser, q)));
    }

    @PutMapping("/api/cards/{cardId}/enable")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CardSummary>> enableCard(
            @PathVariable Long cardId, @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                cardService.setCardEnabled(currentUser, cardId, true)));
    }

    @PutMapping("/api/cards/{cardId}/disable")
    @PreAuthorize("hasAnyAuthority('PERM_CARD_TENANT_MANAGE','PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<CardSummary>> disableCard(
            @PathVariable Long cardId, @AuthenticationPrincipal UserAccount currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                cardService.setCardEnabled(currentUser, cardId, false)));
    }

    // ============ 平台路径（跨租户）============

    @GetMapping("/api/admin/cards/batches")
    @PreAuthorize("hasAuthority('PERM_CARD_CROSS_TENANT_MANAGE')")
    public ResponseEntity<ApiResponse<BatchPageResponse>> listAllBatches(
            @AuthenticationPrincipal UserAccount currentUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String denomination,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        BatchQuery q = new BatchQuery(keyword, status, denomination, tenantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(cardService.listBatches(currentUser, q)));
    }
}
