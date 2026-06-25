-- 为安全重试与分层调度补充最小运行时状态。
-- Gateway 只上报脱敏事件；Platform 幂等落库后通过既有 Outbox / Projector 发布新快照。

ALTER TABLE provider_channel_credential
    ADD COLUMN IF NOT EXISTS billing_account_group VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS quota_scope VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS traffic_weight INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS max_concurrent_streams INTEGER NOT NULL DEFAULT 2147483647;

ALTER TABLE provider_channel_credential
    DROP CONSTRAINT IF EXISTS chk_pcc_traffic_weight,
    ADD CONSTRAINT chk_pcc_traffic_weight CHECK (traffic_weight BETWEEN 1 AND 100000);

ALTER TABLE provider_channel_credential
    DROP CONSTRAINT IF EXISTS chk_pcc_max_concurrent_streams,
    ADD CONSTRAINT chk_pcc_max_concurrent_streams CHECK (max_concurrent_streams BETWEEN 1 AND 2147483647);

COMMENT ON COLUMN provider_channel_credential.billing_account_group IS '上游账务账户组；多把凭证共享同一上游余额时填写相同值，Gateway 按组排除与均衡，不含密钥或厂商账号明文';
COMMENT ON COLUMN provider_channel_credential.quota_scope IS '上游限流池；多把凭证共享同一 RPM/TPM 池时填写相同值，Gateway 按池排除与均衡，不含密钥或请求内容';
COMMENT ON COLUMN provider_channel_credential.traffic_weight IS '绑定流量权重：同一 quotaScope 内 Credential 级加权最少活跃流使用，默认 1';
COMMENT ON COLUMN provider_channel_credential.max_concurrent_streams IS '单绑定最大并发 Attempt 数；Gateway Redis 租约按该硬上限保护，默认近似无限制';

CREATE TABLE upstream_runtime_failure_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    credential_id BIGINT NULL,
    provider_channel_credential_id BIGINT NULL,
    provider_channel_id BIGINT NULL,
    provider_channel_model_id BIGINT NULL,
    route_target_id BIGINT NULL,
    billing_account_group VARCHAR(128) NULL,
    quota_scope VARCHAR(128) NULL,
    failure_kind VARCHAR(64) NOT NULL,
    failure_scope VARCHAR(64) NOT NULL,
    http_status INTEGER NULL,
    retry_after_ms BIGINT NULL,
    execution_certainty VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_upstream_runtime_failure_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT chk_upstream_runtime_failure_retry_after CHECK (retry_after_ms IS NULL OR retry_after_ms >= 0)
);

COMMENT ON TABLE upstream_runtime_failure_event IS 'Gateway 上报的上游运行时故障事件收据：仅包含脱敏资源 ID、失败分类和冷却信息，不保存 API Key、上游 Key、BaseUrl、正文或异常栈';
COMMENT ON COLUMN upstream_runtime_failure_event.event_id IS 'Gateway 生成的幂等事件 ID；重复消费时保证只处理一次';
COMMENT ON COLUMN upstream_runtime_failure_event.tenant_id IS '故障所属租户，用于运行时状态和快照影响范围收敛';
COMMENT ON COLUMN upstream_runtime_failure_event.request_id IS '客户端请求 ID；多个内部 Attempt 共享同一 requestId';
COMMENT ON COLUMN upstream_runtime_failure_event.attempt_id IS '内部上游尝试 ID；每次调度租约对应一个独立 Attempt';
COMMENT ON COLUMN upstream_runtime_failure_event.attempt_no IS '当前请求内第几次 Attempt，从 1 开始';
COMMENT ON COLUMN upstream_runtime_failure_event.occurred_at IS 'Gateway 识别故障的时间';
COMMENT ON COLUMN upstream_runtime_failure_event.credential_id IS '被影响的 provider_credential.id；仅为内部数字 ID，不含凭证内容';
COMMENT ON COLUMN upstream_runtime_failure_event.provider_channel_credential_id IS '被影响的通道凭证绑定 ID';
COMMENT ON COLUMN upstream_runtime_failure_event.provider_channel_id IS '被影响的上游通道 ID';
COMMENT ON COLUMN upstream_runtime_failure_event.provider_channel_model_id IS '被影响的通道模型 ID';
COMMENT ON COLUMN upstream_runtime_failure_event.route_target_id IS '被影响的路由目标 ID';
COMMENT ON COLUMN upstream_runtime_failure_event.billing_account_group IS '被影响的上游账务账户组；来自安全配置，不含账号明文';
COMMENT ON COLUMN upstream_runtime_failure_event.quota_scope IS '被影响的上游限流池；来自安全配置，不含密钥或正文';
COMMENT ON COLUMN upstream_runtime_failure_event.failure_kind IS 'Gateway 统一失败分类，例如 AUTH_INVALID、RATE_LIMITED';
COMMENT ON COLUMN upstream_runtime_failure_event.failure_scope IS '失败影响范围，例如 CREDENTIAL、QUOTA_SCOPE';
COMMENT ON COLUMN upstream_runtime_failure_event.http_status IS '上游 HTTP 状态码；仅用于分类审计，不对用户透出';
COMMENT ON COLUMN upstream_runtime_failure_event.retry_after_ms IS '结构化 Retry-After 转换后的毫秒冷却时间；为空表示无明确建议';
COMMENT ON COLUMN upstream_runtime_failure_event.execution_certainty IS '执行确定性：NOT_EXECUTED、PRE_EXECUTION_REJECTED 或 POSSIBLY_EXECUTED';
COMMENT ON COLUMN upstream_runtime_failure_event.created_at IS 'Platform 消费并持久化该事件的时间';

CREATE INDEX idx_upstream_runtime_failure_tenant_time
    ON upstream_runtime_failure_event (tenant_id, occurred_at DESC);

CREATE TABLE upstream_runtime_resource_state (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    scope_type VARCHAR(64) NOT NULL,
    scope_key VARCHAR(192) NOT NULL,
    runtime_state VARCHAR(64) NOT NULL,
    last_failure_kind VARCHAR(64) NULL,
    last_failed_at TIMESTAMPTZ NULL,
    cooldown_until TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_upstream_runtime_resource_state UNIQUE (scope_type, scope_key),
    CONSTRAINT chk_upstream_runtime_scope_type CHECK (scope_type IN (
        'CREDENTIAL','PROVIDER_CHANNEL_CREDENTIAL','BILLING_ACCOUNT_GROUP','QUOTA_SCOPE',
        'ROUTE_TARGET','PROVIDER_CHANNEL_MODEL','PROVIDER_CHANNEL'
    )),
    CONSTRAINT chk_upstream_runtime_state CHECK (runtime_state IN (
        'AVAILABLE','AUTH_FAILED','BILLING_EXHAUSTED','RATE_LIMITED',
        'MODEL_MAPPING_INVALID','PERMISSION_DENIED','QUARANTINED'
    ))
);

COMMENT ON TABLE upstream_runtime_resource_state IS '上游资源运行时可用状态：由 Gateway 故障事件和管理员恢复操作更新，再经 Outbox 投影为 Redis Snapshot';
COMMENT ON COLUMN upstream_runtime_resource_state.tenant_id IS '状态所属租户；字符串 Scope 以租户前缀隔离，数字 ID Scope 仍保留租户便于审计';
COMMENT ON COLUMN upstream_runtime_resource_state.scope_type IS '状态作用域类型，与 Gateway FailureScope 对齐';
COMMENT ON COLUMN upstream_runtime_resource_state.scope_key IS '状态作用域安全键；数字资源使用 ID 字符串，quotaScope/billingAccountGroup 使用 tenantId:scopeValue';
COMMENT ON COLUMN upstream_runtime_resource_state.runtime_state IS '运行时状态：AVAILABLE 可调度；其他状态由 Snapshot 构建器决定是否参与调度或目录展示';
COMMENT ON COLUMN upstream_runtime_resource_state.last_failure_kind IS '最近一次导致状态变化的失败分类';
COMMENT ON COLUMN upstream_runtime_resource_state.last_failed_at IS '最近一次失败发生时间';
COMMENT ON COLUMN upstream_runtime_resource_state.cooldown_until IS '冷却结束时间；短期状态到期后 Gateway 可重新选择，长期状态需管理员或新快照恢复';
COMMENT ON COLUMN upstream_runtime_resource_state.updated_at IS '状态最后更新时间';

CREATE INDEX idx_upstream_runtime_state_tenant_scope
    ON upstream_runtime_resource_state (tenant_id, scope_type, runtime_state);
