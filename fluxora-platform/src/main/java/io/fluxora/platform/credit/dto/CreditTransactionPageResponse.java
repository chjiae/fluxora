package io.fluxora.platform.credit.dto;

import java.util.List;

public record CreditTransactionPageResponse(
        List<CreditTransactionView> items,
        long total,
        int page,
        int size
) {}
