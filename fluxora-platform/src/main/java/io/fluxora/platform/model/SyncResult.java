package io.fluxora.platform.model;
/** 仅安全汇总同步结果，不携带原始上游响应或凭证明文。 */
public record SyncResult(int added,int updated,int skipped,int failed,java.util.List<SyncItemResult> items) {}
