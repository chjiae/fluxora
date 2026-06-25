-- ============================================================================
-- Fluxora V15：请求余额预冻结、异步结算、释放与待对账。
-- 可用余额沿用 user_credit_account.balance；新增 frozen_balance 不创建第二套钱包。
-- 所有金额采用 CNY NUMERIC(24,8)，所有请求参数、流水与终态均按 request_id 幂等。
-- ============================================================================

ALTER TABLE user_credit_account
    ADD COLUMN IF NOT EXISTS frozen_balance NUMERIC(24,8) NOT NULL DEFAULT 0;
ALTER TABLE user_credit_account
    ADD CONSTRAINT chk_credit_frozen_balance_non_negative CHECK (frozen_balance >= 0);
COMMENT ON COLUMN user_credit_account.balance IS '可用余额：可立即用于新的预冻结或管理员扣减，固定 CNY 八位小数且永不为负';
COMMENT ON COLUMN user_credit_account.frozen_balance IS '冻结余额：已为模型请求预冻结、尚未结算或释放的金额；不得为负且不能直接对外扣减';

ALTER TABLE credit_transaction
    ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_ADJUSTMENT',
    ADD COLUMN IF NOT EXISTS reservation_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS frozen_balance_before NUMERIC(24,8) NULL,
    ADD COLUMN IF NOT EXISTS frozen_balance_after NUMERIC(24,8) NULL;
ALTER TABLE credit_transaction DROP CONSTRAINT IF EXISTS chk_credit_txn_source;
ALTER TABLE credit_transaction ADD CONSTRAINT chk_credit_txn_source
    CHECK (source IN ('MANUAL_ADJUSTMENT', 'CARD_REDEEM', 'BILLING'));
ALTER TABLE credit_transaction ADD CONSTRAINT chk_credit_txn_type
    CHECK (transaction_type IN ('MANUAL_ADJUSTMENT', 'CARD_REDEEM', 'RESERVE', 'SETTLE', 'RELEASE'));
COMMENT ON COLUMN credit_transaction.transaction_type IS '流水业务类型：MANUAL_ADJUSTMENT、CARD_REDEEM、RESERVE、SETTLE 或 RELEASE；既有 direction 保留可用余额增减方向';
COMMENT ON COLUMN credit_transaction.reservation_id IS '关联的请求预冻结记录；仅预冻结、结算和释放流水填写，普通管理或卡密流水为空';
COMMENT ON COLUMN credit_transaction.frozen_balance_before IS '本次流水发生前冻结余额快照；历史非冻结流水为零或空';
COMMENT ON COLUMN credit_transaction.frozen_balance_after IS '本次流水发生后冻结余额快照；历史非冻结流水为零或空';

CREATE TABLE billing_reservation (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    user_id BIGINT NOT NULL REFERENCES user_account(id),
    api_key_id BIGINT NOT NULL REFERENCES api_key(id),
    wallet_id BIGINT NULL REFERENCES user_credit_account(id),
    currency_code VARCHAR(3) NOT NULL,
    reservation_amount NUMERIC(24,8) NOT NULL,
    actual_amount NUMERIC(24,8) NULL,
    settled_amount NUMERIC(24,8) NOT NULL DEFAULT 0,
    released_amount NUMERIC(24,8) NOT NULL DEFAULT 0,
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
    input_token_ceiling BIGINT NOT NULL,
    output_token_ceiling BIGINT NOT NULL,
    cache_write_token_ceiling BIGINT NOT NULL,
    cache_read_token_ceiling BIGINT NOT NULL,
    upstream_dispatch_state VARCHAR(32) NOT NULL DEFAULT 'NOT_DISPATCHED',
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finalized_at TIMESTAMPTZ NULL,
    reconciled_at TIMESTAMPTZ NULL,
    reconciled_by BIGINT NULL REFERENCES user_account(id),
    reconciliation_note VARCHAR(256) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reservation_amount_nonnegative CHECK (
        reservation_amount >= 0 AND settled_amount >= 0 AND released_amount >= 0 AND outstanding_amount >= 0),
    CONSTRAINT chk_reservation_token_nonnegative CHECK (
        input_token_ceiling >= 0 AND output_token_ceiling >= 0
        AND cache_write_token_ceiling >= 0 AND cache_read_token_ceiling >= 0),
    CONSTRAINT chk_reservation_status CHECK (status IN (
        'RESERVED', 'SETTLED', 'RELEASED', 'RECONCILIATION_PENDING', 'RESERVE_REJECTED')),
    CONSTRAINT chk_reservation_dispatch_state CHECK (upstream_dispatch_state IN (
        'NOT_DISPATCHED', 'DISPATCHED', 'RESPONSE_STARTED', 'UNKNOWN'))
);
COMMENT ON TABLE billing_reservation IS '模型请求余额预冻结事实表：一条 request_id 仅能冻结一次；价格、Token 上限与最终处理均保留不可变审计快照';
COMMENT ON COLUMN billing_reservation.request_id IS 'Gateway 生成的随机请求标识，也是预冻结、终态事件与人工对账的幂等主键';
COMMENT ON COLUMN billing_reservation.request_fingerprint IS '预冻结核心参数的 SHA-256 摘要；同 request_id 参数不同必须拒绝，摘要不包含 API Key 或凭证明文';
COMMENT ON COLUMN billing_reservation.wallet_id IS '关联现有用户额度账户；账户不存在时记录 RESERVE_REJECTED，绝不虚构影子钱包';
COMMENT ON COLUMN billing_reservation.reservation_amount IS '调用上游前冻结的最大金额，按请求开始时价格快照和四类 Token 上限计算';
COMMENT ON COLUMN billing_reservation.actual_amount IS '上游完整 usage 计算出的实际金额；未知、部分或超额场景可为空或只作待对账记录';
COMMENT ON COLUMN billing_reservation.outstanding_amount IS '已知实际金额超过冻结金额的差额；本轮禁止自动补扣、负余额与债务处理';
COMMENT ON COLUMN billing_reservation.reason_code IS '安全状态原因，例如 INSUFFICIENT_BALANCE、USAGE_UNKNOWN、ACTUAL_EXCEEDS_RESERVATION；不得保存上游原始错误';
COMMENT ON COLUMN billing_reservation.upstream_dispatch_state IS '安全派发状态：NOT_DISPATCHED、DISPATCHED、RESPONSE_STARTED 或 UNKNOWN；仅 NOT_DISPATCHED 可自动完整释放';
COMMENT ON COLUMN billing_reservation.reconciliation_note IS '平台管理员人工确认的简短审计原因；不允许保存 API Key、上游地址、正文或完整错误';

ALTER TABLE credit_transaction
    ADD CONSTRAINT fk_credit_transaction_reservation
    FOREIGN KEY (reservation_id) REFERENCES billing_reservation(id);
CREATE INDEX idx_billing_reservation_tenant_created ON billing_reservation(tenant_id, created_at DESC);
CREATE INDEX idx_billing_reservation_user_created ON billing_reservation(user_id, created_at DESC);
CREATE INDEX idx_billing_reservation_status_created ON billing_reservation(status, created_at ASC);
CREATE INDEX idx_credit_txn_reservation ON credit_transaction(reservation_id) WHERE reservation_id IS NOT NULL;

ALTER TABLE relay_request_log
    ADD COLUMN IF NOT EXISTS upstream_dispatch_state VARCHAR(32) NOT NULL DEFAULT 'NOT_DISPATCHED',
    ADD COLUMN IF NOT EXISTS reservation_status VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS reservation_amount NUMERIC(24,8) NULL,
    ADD COLUMN IF NOT EXISTS actual_amount NUMERIC(24,8) NULL,
    ADD COLUMN IF NOT EXISTS released_amount NUMERIC(24,8) NULL;
ALTER TABLE relay_request_log ADD CONSTRAINT chk_relay_dispatch_state CHECK (upstream_dispatch_state IN (
    'NOT_DISPATCHED', 'DISPATCHED', 'RESPONSE_STARTED', 'UNKNOWN'));
COMMENT ON COLUMN relay_request_log.upstream_dispatch_state IS '终态判断使用的安全上游派发状态；不记录上游 URL、正文或原始错误';
COMMENT ON COLUMN relay_request_log.reservation_status IS '关联预冻结的当前安全状态，供请求详情展示；不替代 billing_reservation 审计事实';
COMMENT ON COLUMN relay_request_log.reservation_amount IS '请求开始时预冻结金额；金额以字符串 API 传输，前端不得使用浮点计算';
COMMENT ON COLUMN relay_request_log.actual_amount IS '完整 usage 已知时的最终实际金额；未知或待对账时为空';
COMMENT ON COLUMN relay_request_log.released_amount IS '结算后释放回可用余额的金额；待对账时为空或零';

-- TenantModel 的最小计费保护上限。Gateway 使用请求 UTF-8 字节数作为输入 Token 的保守上界，
-- 并拒绝超出以下配置的请求，避免“冻结较小、上游可执行较大”的不一致。
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
COMMENT ON COLUMN tenant_model.max_cache_write_tokens IS '缓存写入 Token 的最大可冻结上限；对应价格存在且请求可能使用缓存时必须能覆盖安全上界';
COMMENT ON COLUMN tenant_model.max_cache_read_tokens IS '缓存读取 Token 的最大可冻结上限；对应价格存在且请求可能使用缓存时必须能覆盖安全上界';
