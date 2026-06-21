package io.fluxora.platform.identity.dto;

import java.util.List;

/**
 * 成员管理列表分页响应。
 *
 * items 为当前页的成员摘要；total 为符合查询条件的成员总数；
 * page、size 与请求参数对齐，便于前端分页组件展示。
 */
public record MemberPageResponse(
        List<MemberSummary> items,
        long total,
        int page,
        int size
) {}
