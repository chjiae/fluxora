-- ============================================================================
-- Fluxora V12：运行时配置快照基础设施、API Key Lookup HMAC 与凭证池关联。
--
-- 本迁移将控制面收敛为 PostgreSQL 事实来源，Redis 仅保存可重建的运行时派生快照：
--   1. runtime_outbox 保证业务写入与运行时刷新意图在同一事务提交；
--   2. runtime_snapshot_version 为每个 Scope 分配严格单调递增版本，阻止乱序投影回退；
--   3. runtime_projection_state 保存时间扫描与重建协调状态，不保存任何密钥；
--   4. api_key 使用 canonical API Key 的 HMAC Lookup 摘要，Gateway 无需数据库或慢哈希；
--   5. provider_credential 与 provider_channel_credential 形成凭证池多对多绑定，
--      一个同租户凭证可绑定多个同租户通道，Gateway 仅取得“是否存在可用绑定”。
--
-- 本迁移不写 Redis、不发布消息、不保存 API Key 或上游凭证明文/密文副本。
-- 所有运行时 SQL 继续由 MyBatis XML 执行，禁止 Java 注解 SQL。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 一、API Key Gateway Lookup
--
-- 旧 key_hash 仅覆盖密钥段，无法在 Gateway 中按完整 header 安全定位。
-- 历史明文已按安全要求不可恢复，故不能为历史行伪造有效 HMAC；将其停用并标记为
-- lookup_hash_version=0，要求用户重新创建 Key。该 Key 过去尚未接入 Gateway，不存在
-- 已上线数据面的兼容承诺。新 Key 必须写入 version=1 的完整 canonical Key HMAC。
-- ----------------------------------------------------------------------------
ALTER TABLE api_key ADD COLUMN lookup_hash CHAR(64);
ALTER TABLE api_key ADD COLUMN lookup_hash_version SMALLINT NOT NULL DEFAULT 0;

UPDATE api_key
SET lookup_hash = LPAD(TO_HEX(id), 64, '0'),
    lookup_hash_version = 0,
    enabled = FALSE,
    updated_at = NOW()
WHERE lookup_hash IS NULL;

ALTER TABLE api_key ALTER COLUMN lookup_hash SET NOT NULL;
ALTER TABLE api_key DROP COLUMN key_hash;

ALTER TABLE api_key
    ADD CONSTRAINT chk_api_key_lookup_hash_version
    CHECK (lookup_hash_version IN (0, 1));
ALTER TABLE api_key
    ADD CONSTRAINT chk_api_key_lookup_hash_hex
    CHECK (lookup_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX uk_api_key_lookup_hash_active
    ON api_key (lookup_hash)
    WHERE deleted_at IS NULL AND lookup_hash_version = 1;
CREATE INDEX idx_api_key_runtime_lookup
    ON api_key (lookup_hash, tenant_id, user_id)
    WHERE deleted_at IS NULL AND lookup_hash_version = 1;

COMMENT ON COLUMN api_key.lookup_hash IS 'Gateway 查找摘要：HMAC-SHA-256(APIKEY_LOOKUP_SECRET, canonical API Key) 的 64 位小写十六进制值；绝不保存明文；version=0 仅表示不可重建的历史停用 Key';
COMMENT ON COLUMN api_key.lookup_hash_version IS 'Lookup 摘要算法版本：1 为当前完整 API Key HMAC；0 为迁移前不可安全重建且已停用的历史记录';

-- ----------------------------------------------------------------------------
-- 二、ProviderCredential → ProviderChannelCredential 多对多凭证池
--
-- 先由历史 provider_credential.provider_channel_id 建立绑定记录；同租户不同通道的
-- 同一指纹被合并为一条凭证加多条绑定，保留最早凭证的密文，重复行软删除用于审计。
-- ----------------------------------------------------------------------------
CREATE TABLE provider_channel_credential (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id),
    provider_credential_id BIGINT NOT NULL REFERENCES provider_credential(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE provider_channel_credential IS '通道与上游凭证绑定表：同租户的一个凭证可绑定多个通道；Gateway 仅根据有效绑定判断凭证池可用性，不读取密文';
COMMENT ON COLUMN provider_channel_credential.tenant_id IS '绑定所属租户；服务层强制与通道和凭证的 tenant_id 一致，禁止跨租户共享凭证';
COMMENT ON COLUMN provider_channel_credential.provider_channel_id IS '可使用该凭证池的租户通道；通道软删除或停用后绑定不参与运行时快照';
COMMENT ON COLUMN provider_channel_credential.provider_credential_id IS '被绑定的加密凭证；本轮 Gateway 不解密、不选择具体凭证，仅检查是否存在有效绑定';
COMMENT ON COLUMN provider_channel_credential.enabled IS '绑定启用状态：false 时不影响凭证实体本身，但当前通道不能使用该绑定';
COMMENT ON COLUMN provider_channel_credential.deleted_at IS '逻辑删除时间戳：NULL 表示有效绑定；非 NULL 表示解绑时刻，用于审计、未来恢复与排查';
COMMENT ON COLUMN provider_channel_credential.created_at IS '绑定创建时间';
COMMENT ON COLUMN provider_channel_credential.updated_at IS '绑定最后修改时间；运行时快照据此识别凭证池配置变化';

CREATE UNIQUE INDEX uk_provider_channel_credential_active
    ON provider_channel_credential (provider_channel_id, provider_credential_id)
    WHERE deleted_at IS NULL;

WITH ranked_credentials AS (
    SELECT id,
           tenant_id,
           provider_channel_id,
           enabled,
           FIRST_VALUE(id) OVER (
               PARTITION BY tenant_id, credential_fingerprint
               ORDER BY created_at ASC, id ASC
           ) AS canonical_credential_id
    FROM provider_credential
    WHERE deleted_at IS NULL
)
INSERT INTO provider_channel_credential (
    tenant_id, provider_channel_id, provider_credential_id, enabled, created_at, updated_at
)
SELECT tenant_id, provider_channel_id, canonical_credential_id, enabled, NOW(), NOW()
FROM ranked_credentials
ON CONFLICT DO NOTHING;

WITH ranked_credentials AS (
    SELECT id,
           FIRST_VALUE(id) OVER (
               PARTITION BY tenant_id, credential_fingerprint
               ORDER BY created_at ASC, id ASC
           ) AS canonical_credential_id
    FROM provider_credential
    WHERE deleted_at IS NULL
)
UPDATE provider_credential credential
SET deleted_at = NOW(),
    updated_at = NOW(),
    enabled = FALSE
FROM ranked_credentials ranked
WHERE credential.id = ranked.id
  AND ranked.id <> ranked.canonical_credential_id;

DROP INDEX IF EXISTS uk_provider_credential_channel_fingerprint_active;
CREATE UNIQUE INDEX uk_provider_credential_tenant_fingerprint_active
    ON provider_credential (tenant_id, credential_fingerprint)
    WHERE deleted_at IS NULL;

ALTER TABLE provider_credential DROP COLUMN provider_channel_id;

CREATE INDEX idx_provider_channel_credential_runtime
    ON provider_channel_credential (provider_channel_id, enabled, provider_credential_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_provider_credential_binding_runtime
    ON provider_channel_credential (provider_credential_id, enabled, provider_channel_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE provider_credential IS '上游访问凭证：仅保存加密密文与去重指纹；通过 provider_channel_credential 绑定到一个或多个同租户通道，绝不向 Gateway 下发密文';
COMMENT ON COLUMN provider_credential.tenant_id IS '凭证所属租户；相同明文仅允许在同一租户保留一个未删除凭证实体，跨通道通过绑定复用';
COMMENT ON COLUMN provider_credential.enabled IS '凭证实体启用状态：false 时所有绑定通道均不可使用该凭证；绑定自身仍可独立停用';
COMMENT ON COLUMN provider_credential.credential_fingerprint IS 'HMAC-SHA-256 去重指纹；仅用于同租户凭证去重和迁移归并，绝不返回接口、日志或运行时快照';
COMMENT ON COLUMN provider_credential.ciphertext IS 'AES-GCM 加密密文；只允许控制面内部加解密流程访问，严禁进入 Redis、Gateway、DTO 或日志';
COMMENT ON COLUMN provider_credential.initialization_vector IS 'AES-GCM 初始化向量；仅控制面内部解密需要，严禁进入 Redis、Gateway、DTO 或日志';

-- ----------------------------------------------------------------------------
-- 三、Runtime Outbox：与控制面业务写入同事务提交的可靠投影意图。
-- ----------------------------------------------------------------------------
CREATE TABLE runtime_outbox (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NULL REFERENCES tenant(id),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NULL,
    mutation_type VARCHAR(64) NOT NULL,
    impact_hint VARCHAR(128) NULL,
    payload_version SMALLINT NOT NULL DEFAULT 1,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_by VARCHAR(128) NULL,
    locked_at TIMESTAMPTZ NULL,
    last_error_summary VARCHAR(512) NULL,
    projected_at TIMESTAMPTZ NULL,
    notified_at TIMESTAMPTZ NULL,
    processed_at TIMESTAMPTZ NULL,
    CONSTRAINT chk_runtime_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETED')),
    CONSTRAINT chk_runtime_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_runtime_outbox_payload_version CHECK (payload_version = 1)
);

COMMENT ON TABLE runtime_outbox IS '运行时配置投影 Outbox：与控制面业务写入同一 PostgreSQL 事务提交；Projector 以幂等、可重试方式生成 Redis 运行时快照';
COMMENT ON COLUMN runtime_outbox.tenant_id IS '受影响租户；平台共享资源可为空，由 RuntimeImpactResolver 根据关联关系计算实际 Scope';
COMMENT ON COLUMN runtime_outbox.aggregate_type IS '发生变更的领域实体类型；业务服务只记录来源实体，禁止自行决定 Redis Key 或 Gateway 缓存';
COMMENT ON COLUMN runtime_outbox.aggregate_id IS '发生变更的实体主键；为空仅用于系统级全量重建或时间状态扫描任务';
COMMENT ON COLUMN runtime_outbox.mutation_type IS '标准化变更类型；ImpactResolver 根据实体与操作计算最小受影响运行时 Scope';
COMMENT ON COLUMN runtime_outbox.impact_hint IS '可选影响提示；仅用于缩小数据库关联查询范围，不承载完整配置或敏感信息';
COMMENT ON COLUMN runtime_outbox.payload_version IS 'Outbox 负载契约版本；本轮固定 1，升级时必须保持旧消费者可识别';
COMMENT ON COLUMN runtime_outbox.occurred_at IS '业务变更发生时间；用于排序、延迟观测和安全审计';
COMMENT ON COLUMN runtime_outbox.status IS '投影状态：PENDING 待处理、PROCESSING 已抢占、RETRY 等待退避、COMPLETED 快照与通知均成功';
COMMENT ON COLUMN runtime_outbox.attempt_count IS '累计投影尝试次数；用于指数退避与失败可观测性';
COMMENT ON COLUMN runtime_outbox.next_retry_at IS '下一次可领取时间；索引支持 Projector 高效扫描';
COMMENT ON COLUMN runtime_outbox.locked_by IS '抢占本记录的 Projector 实例标识；仅运维与故障排查使用';
COMMENT ON COLUMN runtime_outbox.locked_at IS '本次抢占时间；超时记录可由恢复任务安全重新领取';
COMMENT ON COLUMN runtime_outbox.last_error_summary IS '安全截断的失败摘要；不得包含 API Key、凭证明文/密文、SQL、堆栈或配置内容';
COMMENT ON COLUMN runtime_outbox.projected_at IS '不可变快照与 Manifest 成功切换时间';
COMMENT ON COLUMN runtime_outbox.notified_at IS 'Redis Pub/Sub 失效通知成功发布的时间';
COMMENT ON COLUMN runtime_outbox.processed_at IS '完整投影处理成功时间';

CREATE INDEX idx_runtime_outbox_claim
    ON runtime_outbox (status, next_retry_at, id)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_runtime_outbox_tenant_pending
    ON runtime_outbox (tenant_id, occurred_at DESC)
    WHERE status <> 'COMPLETED';

-- ----------------------------------------------------------------------------
-- 四、Scope 版本与协调状态。
-- ----------------------------------------------------------------------------
CREATE TABLE runtime_snapshot_version (
    scope_type VARCHAR(64) NOT NULL,
    scope_key VARCHAR(512) NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scope_type, scope_key),
    CONSTRAINT chk_runtime_snapshot_version_positive CHECK (current_version >= 0)
);

COMMENT ON TABLE runtime_snapshot_version IS '运行时快照版本分配表：按最小 Scope 持久化单调递增版本，防止重复、乱序或旧 Projector 覆盖高版本 Manifest';
COMMENT ON COLUMN runtime_snapshot_version.scope_type IS 'Scope 类型：AUTH_API_KEY、AUTH_USER、AUTH_TENANT 或 TENANT_MODEL_ROUTE';
COMMENT ON COLUMN runtime_snapshot_version.scope_key IS 'Scope 的安全逻辑键；API Key Scope 仅使用 HMAC 摘要，模型编码使用稳定编码，不写 Redis 原始 Key';
COMMENT ON COLUMN runtime_snapshot_version.current_version IS '当前已分配的严格递增运行时版本号；必须由单条 UPSERT 原子加一';
COMMENT ON COLUMN runtime_snapshot_version.updated_at IS '最近一次版本分配时间；用于运行时滞后观测与故障排查';

CREATE TABLE runtime_projection_state (
    state_key VARCHAR(128) PRIMARY KEY,
    state_value VARCHAR(512) NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE runtime_projection_state IS '运行时投影协调状态：保存时间边界扫描游标和重建标记，不保存 API Key、凭证、上游配置或用户隐私';
COMMENT ON COLUMN runtime_projection_state.state_key IS '状态名称，例如 API_KEY_TIME_SCAN_CURSOR；由 Platform 内部固定枚举管理';
COMMENT ON COLUMN runtime_projection_state.state_value IS '安全的协调值，例如 ISO-8601 扫描游标；不得写入 Redis Key、明文密钥或错误堆栈';
COMMENT ON COLUMN runtime_projection_state.updated_at IS '协调状态最近更新时间';
