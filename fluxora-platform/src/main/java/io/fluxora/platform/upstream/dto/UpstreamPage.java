package io.fluxora.platform.upstream.dto;

import java.util.List;

/**
 * 上游配置分页响应。
 * 仅承载脱敏后的 Summary 列表与分页元数据，不包含任何加密字段或 deletedAt。
 *
 * @param items 当前页数据
 * @param total 符合筛选条件的总条数
 * @param page  当前页码（1 基）
 * @param size  每页大小
 */
public record UpstreamPage<T>(List<T> items, long total, int page, int size) {
}
