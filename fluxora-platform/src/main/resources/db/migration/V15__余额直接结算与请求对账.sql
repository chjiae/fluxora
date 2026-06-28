-- ============================================================================
-- Fluxora V15：模型请求直接结算、余额准入与待对账。
-- 用户余额是唯一金额事实源；Gateway 不同步查询余额，Platform 在终态直接结算。
-- 已放行请求按可信 usage 直接扣费，余额允许从正数变为 0 或负数；新的准入状态通过运行时快照异步同步。
-- ============================================================================

ALTER TABLE credit_transaction
    ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_ADJUSTMENT',
    ADD COLUMN IF NOT EXISTS billing_settlement_id BIGINT NULL;
ALTER TABLE credit_transaction DROP CONSTRAINT IF EXISTS chk_credit_txn_source;
ALTER TABLE credit_transaction ADD CONSTRAINT chk_credit_txn_source
    CHECK (source IN ('MANUAL_ADJUSTMENT', 'CARD_REDEEM', 'BILLING'));
ALTER TABLE credit_transaction ADD CONSTRAINT chk_credit_txn_type
    CHECK (transaction_type IN ('MANUAL_ADJUSTMENT', 'CARD_REDEEM', 'MODEL_USAGE'));
COMMENT ON COLUMN credit_transaction.transaction_type IS '流水业务类型：MANUAL_ADJUSTMENT、CARD_REDEEM 或 MODEL_USAGE；模型请求按最终可信用量直接扣费';
COMMENT ON COLUMN credit_transaction.billing_settlement_id IS '关联的模型请求直接结算记录；仅 MODEL_USAGE 流水填写，普通管理或卡密流水为空';

CREATE TABLE billing_settlement (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    user_id BIGINT NOT NULL REFERENCES user_account(id),
    api_key_id BIGINT NOT NULL REFERENCES api_key(id),
    currency_code VARCHAR(3) NOT NULL,
    actual_amount NUMERIC(24,8) NULL,
    outstanding_amount NUMERIC(24,8) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NULL,
    tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id),
    tenant_model_code VARCHAR(128) NOT NULL,
    inbound_protocol VARCHAR(32) NOT NULL,
    endpoint VARCHAR(128) NOT NULL,
    price_version INTEGER NOT NULL,
    input_price_per_million NUMERIC(24,8) NOT NULL,
    output_price_per_million NUMERIC(24,8) NOT NULL,
    cache_write_price_per_million NUMERIC(24,8) NULL,
    cache_read_price_per_million NUMERIC(24,8) NULL,
    upstream_dispatch_state VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    finalized_at TIMESTAMPTZ NULL,
    reconciled_at TIMESTAMPTZ NULL,
    reconciled_by BIGINT NULL REFERENCES user_account(id),
    reconciliation_note VARCHAR(256) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_settlement_amount_nonnegative CHECK (
        (actual_amount IS NULL OR actual_amount >= 0) AND outstanding_amount >= 0),
    CONSTRAINT chk_settlement_status CHECK (status IN (
        'SETTLED', 'NO_CHARGE', 'RECONCILIATION_PENDING')),
    CONSTRAINT chk_settlement_dispatch_state CHECK (upstream_dispatch_state IN (
        'NOT_DISPATCHED', 'DISPATCHED', 'RESPONSE_STARTED', 'UNKNOWN'))
);
COMMENT ON TABLE billing_settlement IS '模型请求直接结算事实表：一条 request_id 只处理一次；保存价格、用量与对账状态';
COMMENT ON COLUMN billing_settlement.request_id IS 'Gateway 生成的随机请求标识，也是终态事件、直接结算与人工对账的幂等主键';
COMMENT ON COLUMN billing_settlement.actual_amount IS '可信 usage 计算出的最终实扣金额；未知或待对账时为空';
COMMENT ON COLUMN billing_settlement.outstanding_amount IS '当前保留字段，用于未来人工差异说明；本轮直接结算不自动追补未知 usage';
COMMENT ON COLUMN billing_settlement.status IS '结算状态：SETTLED 已直接扣费，NO_CHARGE 明确不扣费，RECONCILIATION_PENDING 等待人工确认';
COMMENT ON COLUMN billing_settlement.reason_code IS '安全状态原因，例如 USAGE_UNKNOWN、TERMINAL_EVENT_TIMEOUT 或 UPSTREAM_NOT_DISPATCHED；不得保存上游原始错误';
COMMENT ON COLUMN billing_settlement.upstream_dispatch_state IS '安全派发状态：NOT_DISPATCHED、DISPATCHED、RESPONSE_STARTED 或 UNKNOWN；仅 NOT_DISPATCHED 可自动不扣费';
COMMENT ON COLUMN billing_settlement.reconciliation_note IS '平台管理员人工确认的简短审计原因；不允许保存 API Key、上游地址、正文或完整错误';

ALTER TABLE credit_transaction
    ADD CONSTRAINT fk_credit_transaction_billing_settlement
    FOREIGN KEY (billing_settlement_id) REFERENCES billing_settlement(id);
CREATE INDEX idx_billing_settlement_tenant_created ON billing_settlement(tenant_id, created_at DESC);
CREATE INDEX idx_billing_settlement_user_created ON billing_settlement(user_id, created_at DESC);
CREATE INDEX idx_billing_settlement_status_created ON billing_settlement(status, created_at ASC);
CREATE INDEX idx_credit_txn_billing_settlement ON credit_transaction(billing_settlement_id) WHERE billing_settlement_id IS NOT NULL;

ALTER TABLE relay_request_log
    ADD COLUMN IF NOT EXISTS upstream_dispatch_state VARCHAR(32) NOT NULL DEFAULT 'NOT_DISPATCHED',
    ADD COLUMN IF NOT EXISTS billing_status VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS actual_amount NUMERIC(24,8) NULL,
    ADD COLUMN IF NOT EXISTS outstanding_amount NUMERIC(24,8) NULL;
ALTER TABLE relay_request_log ADD CONSTRAINT chk_relay_dispatch_state CHECK (upstream_dispatch_state IN (
    'NOT_DISPATCHED', 'DISPATCHED', 'RESPONSE_STARTED', 'UNKNOWN'));
COMMENT ON COLUMN relay_request_log.upstream_dispatch_state IS '终态判断使用的安全上游派发状态；不记录上游 URL、正文或原始错误';
COMMENT ON COLUMN relay_request_log.billing_status IS '关联直接结算的当前安全状态，供请求详情展示；不替代 billing_settlement 审计事实';
COMMENT ON COLUMN relay_request_log.actual_amount IS '完整 usage 已知时的最终实际扣费金额；未知或待对账时为空';
COMMENT ON COLUMN relay_request_log.outstanding_amount IS '待对账差异说明金额；本轮不代表自动追扣或用户可见欠款';

-- TenantModel 的最小计费保护上限。Gateway 使用请求 UTF-8 字节数作为输入 Token 的保守上界，
-- 只用于请求大小和价格估算保护，不产生终态前金额写入。
ALTER TABLE tenant_model
    ADD COLUMN IF NOT EXISTS max_input_tokens BIGINT NOT NULL DEFAULT 32768,
    ADD COLUMN IF NOT EXISTS max_output_tokens BIGINT NOT NULL DEFAULT 8192,
    ADD COLUMN IF NOT EXISTS max_cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS default_output_tokens BIGINT NOT NULL DEFAULT 2048;
ALTER TABLE tenant_model ADD CONSTRAINT chk_tenant_model_token_limits CHECK (
    max_input_tokens > 0 AND max_output_tokens > 0 AND default_output_tokens > 0
    AND default_output_tokens <= max_output_tokens AND max_cache_write_tokens >= 0 AND max_cache_read_tokens >= 0);
COMMENT ON COLUMN tenant_model.max_input_tokens IS '单请求允许的最大输入 Token 保守上限；Gateway 用 UTF-8 请求体字节数作为不低估 Token 的安全上界';
COMMENT ON COLUMN tenant_model.max_output_tokens IS '单请求允许的最大输出 Token；客户端上限超过此值必须安全拒绝，不得静默放大上游请求';
COMMENT ON COLUMN tenant_model.default_output_tokens IS '客户端未声明输出上限时 Gateway 写入并转发的确定性默认上限，必须不大于 max_output_tokens';
COMMENT ON COLUMN tenant_model.max_cache_write_tokens IS '缓存写入 Token 的最大计价保护上限；对应价格存在且请求可能使用缓存时必须能覆盖安全上界';
COMMENT ON COLUMN tenant_model.max_cache_read_tokens IS '缓存读取 Token 的最大计价保护上限；对应价格存在且请求可能使用缓存时必须能覆盖安全上界';
