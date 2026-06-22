package io.fluxora.platform.card.dto;

import java.util.List;

/**
 * 批量创建卡密请求体。
 * 一次可提交多组面额，每组生成独立批次便于统计与审计。
 */
public record CreateBatchRequest(List<DenominationGroup> groups) {}