package io.fluxora.platform.card.dto;

import java.util.List;

/**
 * 批量创建卡密的一次性响应。
 *
 * 关键安全约束：
 *   - {@code batches}：本次创建的批次摘要（多组面额对应多个批次）
 *   - {@code plaintexts}：本次生成的全部完整卡密明文；按面额组顺序排列
 *   - plaintexts 仅在本次响应返回，后续任何接口、列表、详情、刷新均不再返回
 *   - 前端必须仅在内存中保留，并提供导出 TXT / CSV 后清空
 */
public record CreatedBatchResponse(
        List<CardBatchSummary> batches,
        List<String> plaintexts
) {}