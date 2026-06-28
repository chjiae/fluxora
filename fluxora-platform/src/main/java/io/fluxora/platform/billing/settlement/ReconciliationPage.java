package io.fluxora.platform.billing.settlement;

import java.util.List;

/** 管理端待对账分页结果。 */
public record ReconciliationPage(List<BillingSettlementView> items, long total, int page, int size) {
}
