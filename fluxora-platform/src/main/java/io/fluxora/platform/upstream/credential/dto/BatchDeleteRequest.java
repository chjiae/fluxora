package io.fluxora.platform.upstream.credential.dto;

import java.util.List;

/**
 * 批量删除凭证请求。
 * 限定在同一通道作用域内操作；服务层校验租户归属与通道可见性后执行批量软删除。
 */
public record BatchDeleteRequest(
        Long providerChannelId,
        List<Long> ids) {
}
