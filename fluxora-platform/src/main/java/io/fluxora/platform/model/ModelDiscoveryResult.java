package io.fluxora.platform.model;

import java.util.List;

/** 上游发现解析结果：有效模型与解析失败记录分离，便于同步接口返回完整安全统计。 */
public record ModelDiscoveryResult(List<String> modelIds, List<SyncItemResult> failures) {}
