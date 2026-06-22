package io.fluxora.platform.card.dto;

import java.util.List;

public record BatchPageResponse(List<CardBatchSummary> items, long total, int page, int size) {}