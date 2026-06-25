package io.fluxora.platform.billing.reservation;

import java.util.List;

/** 待对账分页，不在内存聚合或循环查询用户资料。 */
public record ReconciliationPage(List<BillingReservationView> items, long total, int page, int size) {
}
