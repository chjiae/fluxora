package io.fluxora.platform.observability;

import java.time.Instant;

/** 已解析权限范围后的查询条件；tenantId 必填，普通成员额外带 userId，禁止跨租户混合读取。 */
record RelayRequestLogQuery(long tenantId, Long userId, String requestId, String tenantModelCode, String protocol,
                            Long apiKeyId, Long filterUserId, String requestStatus, Instant startAt, Instant endAt,
                            int limit, int offset) { }
