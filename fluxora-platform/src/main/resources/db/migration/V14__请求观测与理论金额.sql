-- ============================================================================
-- Fluxora V14：Gateway 中继请求观测、Token 用量与理论金额。
--
-- 事实来源为 Redis Stream 事件；本迁移只保存脱敏元数据、四类 Token 与请求开始时的价格快照。
-- 严禁保存 API Key、Authorization、凭证、BaseUrl、上游模型、请求/响应正文、消息或工具参数。
-- 请求日志为不可变审计事实，不提供普通业务软删除，避免审计链被删除破坏。
-- ============================================================================

CREATE TABLE relay_event_receipt (
    event_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE relay_event_receipt IS 'Redis Stream 中继事件幂等收据：event_id 全局唯一；同一消息重复投递或重复消费时不得重复更新请求日志';
COMMENT ON COLUMN relay_event_receipt.event_id IS 'Gateway 生成的随机事件标识；不含 API Key、用户或模型文本';
COMMENT ON COLUMN relay_event_receipt.request_id IS '关联的随机请求追踪标识，用于排查同一次请求的开始与终态事件';
COMMENT ON COLUMN relay_event_receipt.event_type IS '事件类型：RELAY_REQUEST_STARTED、RELAY_REQUEST_FINISHED、RELAY_REQUEST_FAILED 或 RELAY_REQUEST_CANCELLED';
COMMENT ON COLUMN relay_event_receipt.received_at IS 'Platform 成功写入 PostgreSQL 的接收时间';

CREATE TABLE relay_request_log (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    user_id BIGINT NOT NULL REFERENCES user_account(id),
    api_key_id BIGINT NOT NULL REFERENCES api_key(id),
    inbound_protocol VARCHAR(32) NOT NULL,
    outbound_protocol VARCHAR(32) NOT NULL,
    endpoint VARCHAR(128) NOT NULL,
    tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id),
    tenant_model_code VARCHAR(128) NOT NULL,
    route_target_id BIGINT NOT NULL REFERENCES route_target(id),
    provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id),
    provider_channel_model_id BIGINT NOT NULL REFERENCES provider_channel_model(id),
    stream BOOLEAN NOT NULL DEFAULT FALSE,
    request_status VARCHAR(32) NOT NULL,
    error_category VARCHAR(64) NULL,
    safe_http_status INTEGER NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NULL,
    duration_ms BIGINT NULL,
    usage_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    cache_write_tokens BIGINT NULL,
    cache_read_tokens BIGINT NULL,
    currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY',
    price_version INTEGER NOT NULL,
    input_price_per_million NUMERIC(24,8) NOT NULL,
    output_price_per_million NUMERIC(24,8) NOT NULL,
    cache_write_price_per_million NUMERIC(24,8) NULL,
    cache_read_price_per_million NUMERIC(24,8) NULL,
    theoretical_amount NUMERIC(24,8) NULL,
    pricing_status VARCHAR(32) NOT NULL,
    source_event_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_relay_request_status CHECK (request_status IN ('STARTED','SUCCESS','FAILED','CANCELLED')),
    CONSTRAINT chk_relay_usage_status CHECK (usage_status IN ('REPORTED','PARTIAL','UNKNOWN','NOT_APPLICABLE')),
    CONSTRAINT chk_relay_pricing_status CHECK (pricing_status IN ('CALCULATED','PARTIAL','UNAVAILABLE','NOT_APPLICABLE')),
    CONSTRAINT chk_relay_token_nonnegative CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0) AND (cache_write_tokens IS NULL OR cache_write_tokens >= 0) AND (cache_read_tokens IS NULL OR cache_read_tokens >= 0))
);
COMMENT ON TABLE relay_request_log IS '中继请求安全观测日志：由 Platform 消费 Redis Stream 幂等写入，保存状态、用量与价格快照，不保存正文、密钥、凭证或上游地址';
COMMENT ON COLUMN relay_request_log.request_id IS '随机全局请求追踪标识；跨租户查询仍必须经过 tenant_id 与用户权限校验';
COMMENT ON COLUMN relay_request_log.tenant_id IS '请求所属租户；所有列表、详情与趋势查询首先按此字段收缩范围';
COMMENT ON COLUMN relay_request_log.user_id IS '发起请求的用户；普通成员只能查看本人的记录';
COMMENT ON COLUMN relay_request_log.api_key_id IS '发起请求的 API Key 内部主键；永不存储 API Key 明文或前缀';
COMMENT ON COLUMN relay_request_log.tenant_model_code IS '租户对外模型编码；不存储上游模型标识';
COMMENT ON COLUMN relay_request_log.route_target_id IS '内部路由引用，仅供 Platform 消费与审计关联，公开接口不返回其详细配置';
COMMENT ON COLUMN relay_request_log.stream IS '是否为 SSE 流式中继；不保存任意 SSE 文本分块';
COMMENT ON COLUMN relay_request_log.usage_status IS '用量状态：REPORTED 完整、PARTIAL 部分、UNKNOWN 未上报、NOT_APPLICABLE 未调用上游';
COMMENT ON COLUMN relay_request_log.input_tokens IS '普通输入 Token；缓存读取 Token 已从本字段拆分，NULL 表示未知而非零';
COMMENT ON COLUMN relay_request_log.cache_write_tokens IS '缓存写入 Token；NULL 表示上游未明确报告或当前不适用';
COMMENT ON COLUMN relay_request_log.cache_read_tokens IS '缓存读取 Token；不得同时计入普通输入 Token';
COMMENT ON COLUMN relay_request_log.input_price_per_million IS '请求开始时固定的输入单价快照，每百万 Token、CNY 八位小数';
COMMENT ON COLUMN relay_request_log.theoretical_amount IS '按价格快照在 Platform 高精度重算的理论金额；不代表扣费、余额、账单或结算';
COMMENT ON COLUMN relay_request_log.pricing_status IS '理论金额状态：只有 CALCULATED 行可进入金额汇总，未知或部分用量绝不按零计费';
COMMENT ON COLUMN relay_request_log.source_event_id IS '最后写入本行的 Redis Stream 事件标识；重复 event_id 由 relay_event_receipt 拦截';

CREATE INDEX idx_relay_log_tenant_created ON relay_request_log (tenant_id, created_at DESC);
CREATE INDEX idx_relay_log_user_created ON relay_request_log (user_id, created_at DESC);
CREATE INDEX idx_relay_log_api_key_created ON relay_request_log (api_key_id, created_at DESC);
CREATE INDEX idx_relay_log_tenant_model_created ON relay_request_log (tenant_id, tenant_model_code, created_at DESC);
CREATE INDEX idx_relay_log_tenant_status_created ON relay_request_log (tenant_id, request_status, created_at DESC);
