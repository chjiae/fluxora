package io.fluxora.platform.upstream.credential.dto;

import java.util.List;

/**
 * 批量导入请求。
 * lines 为原始多行文本；服务端逐行清理首尾空白并保留原始大小写，不修改中间字符。
 * namePrefix 可空，用于为本次导入的凭证统一命名；priority/weight/remark 为统一默认值。
 * plaintext 仅在请求处理期间存在，处理完成后不再持有。
 */
public record CredentialImportRequest(
        Long providerChannelId,
        List<String> lines,
        String namePrefix,
        Integer priority,
        Integer weight,
        String remark) {
}
