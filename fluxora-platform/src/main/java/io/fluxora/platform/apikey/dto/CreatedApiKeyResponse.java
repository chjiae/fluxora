package io.fluxora.platform.apikey.dto;

/**
 * 创建 API Key 的一次性响应。
 *
 * 关键安全约束：
 *   - {@code plaintext} 是完整明文 Key（45 字符），形如 flx_XXXXXXXX_YYYY...YYYY；
 *   - 仅在此 DTO 中返回一次；后续任何接口、列表、详情、刷新、重新登录均不再返回；
 *   - 前端必须仅在内存中保留，且在弹窗关闭/路由跳转/页面刷新后立刻清空。
 *
 * summary 仍是脱敏的常规摘要，便于前端创建后立刻把新 Key 插到列表头部展示。
 */
public record CreatedApiKeyResponse(
        ApiKeySummary summary,
        String plaintext
) {}
