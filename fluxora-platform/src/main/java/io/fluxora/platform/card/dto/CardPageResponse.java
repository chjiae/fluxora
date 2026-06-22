package io.fluxora.platform.card.dto;

import java.util.List;

public record CardPageResponse(List<CardSummary> items, long total, int page, int size) {}