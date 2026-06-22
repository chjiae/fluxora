-- ============================================================
-- Fluxora V5：API Key 与用户额度账户
--
-- 本迁移引入运营资产层（控制面）：
--
-- 1. api_key 表：租户用户可生成的访问凭证元数据
--    - 不保存任何明文：完整 Key 仅在创建响应中返回一次
--    - key_prefix 作为 DB 索引列，供未来网关按 prefix 查找；key_hash 为
--      HMAC-SHA256(secret_part, server_pepper) 的 hex（pepper 来自配置，
--      绝不进入日志/堆栈/前端）
--    - 状态四态由 enabled / expire_at / deleted_at 三个字段派生
--      （ENABLED / DISABLED / EXPIRED / DELETED）
--    - 软删除遵循 AGENT.md「软删除字段规范」：deleted_at TIMESTAMPTZ NULL
--    - prefix 唯一索引使用 WHERE deleted_at IS NULL 部分唯一，软删后前缀
--      可被新 Key 占用（不阻塞未来重建）
--
-- 2. user_credit_account 表：用户额度账户
--    - 一对一对应 TENANT 作用域用户；PLATFORM 用户不创建账户
--    - balance 用 DECIMAL(20,4)（16 整数位 + 4 小数位），CHECK 保证非负
--    - 余额变更只能由 CreditService 走 UPDATE…WHERE balance + delta >= 0
--      RETURNING 单语句完成；本表无独立修改接口
--
-- 3. credit_transaction 表：额度流水（不可篡改）
--    - 仅 INSERT；后端不提供 UPDATE / DELETE SQL；表无 deleted_at / updated_at
--    - 与额度调整在同一事务写入；记录 balance_before / balance_after
--      （来自 UPDATE…RETURNING）保证审计连贯
--    - CHECK 约束限制 direction ∈ {CREDIT, DEBIT}、delta > 0
--
-- 4. 数据回填：为所有未软删 TENANT 用户幂等创建 user_credit_account
--
-- 5. 权限种子：新增 7 个细粒度权限码并绑定到既有角色（service 层做
--    最终边界校验，权限码仅作粗粒度网关）
--
-- 所有 DDL / INSERT 设计为幂等（IF EXISTS / IF NOT EXISTS / ON CONFLICT），
-- 重复执行不破坏现有数据。
-- ============================================================

-- ------------------------------------------------------------
-- 一、api_key 表
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS api_key (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant (id),
    user_id       BIGINT       NOT NULL REFERENCES user_account (id),
    name          VARCHAR(64)  NOT NULL,
    key_prefix    VARCHAR(20)  NOT NULL,
    key_hash      CHAR(64)     NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    expire_at     TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  api_key                IS 'API Key 元数据表：仅保存前缀与 HMAC 哈希，绝不保存完整明文；完整 Key 仅在创建响应中返回一次';
COMMENT ON COLUMN api_key.id             IS '主键，自增序列';
COMMENT ON COLUMN api_key.tenant_id      IS '所属租户：与 user_id 联合形成租户内归属关系；用于跨租户隔离与索引剪枝';
COMMENT ON COLUMN api_key.user_id        IS '所属租户用户：仅 TENANT 作用域用户可拥有 API Key';
COMMENT ON COLUMN api_key.name           IS 'Key 名称：用户可读，允许字母/数字/空格/中文/常见标点，长度 2-64';
COMMENT ON COLUMN api_key.key_prefix     IS '公开前缀：格式 flx_XXXXXXXX，作为索引列供网关按前缀快速定位';
COMMENT ON COLUMN api_key.key_hash       IS 'HMAC-SHA256(secret_part, server_pepper) 的 hex，64 字符；网关用其校验输入 Key 真伪';
COMMENT ON COLUMN api_key.enabled        IS '启用状态：false 时即使未过期也视为 DISABLED，不能被使用';
COMMENT ON COLUMN api_key.expire_at      IS '过期时间：超过后视为 EXPIRED；NULL 表示永不过期';
COMMENT ON COLUMN api_key.last_used_at   IS '最后使用时间：本轮预留字段；未来网关回写';
COMMENT ON COLUMN api_key.deleted_at     IS '软删除时间戳：NULL 表示未删除；非 NULL 表示已删除并记录删除时刻（遵循 AGENT.md 软删除规范）';
COMMENT ON COLUMN api_key.created_at     IS '创建时间';
COMMENT ON COLUMN api_key.updated_at     IS '最后更新时间';

-- prefix 在未软删记录中全局唯一；软删后释放，方便重建
CREATE UNIQUE INDEX IF NOT EXISTS uk_api_key_prefix ON api_key (key_prefix) WHERE deleted_at IS NULL;
-- 用户、租户维度的高频查询索引，同样过滤已删除
CREATE INDEX IF NOT EXISTS idx_api_key_user   ON api_key (user_id)   WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON api_key (tenant_id) WHERE deleted_at IS NULL;

-- ------------------------------------------------------------
-- 二、user_credit_account 表
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS user_credit_account (
    id          BIGSERIAL      PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL REFERENCES tenant (id),
    user_id     BIGINT         NOT NULL REFERENCES user_account (id),
    balance     DECIMAL(20, 4) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_credit_balance_non_negative CHECK (balance >= 0)
);

COMMENT ON TABLE  user_credit_account            IS '用户额度账户：与 TENANT 作用域用户一对一对应；本轮仅有可用余额一列，未来可扩展冻结/已用额度';
COMMENT ON COLUMN user_credit_account.id         IS '主键，自增序列';
COMMENT ON COLUMN user_credit_account.tenant_id  IS '所属租户：方便按租户聚合统计与跨租户隔离';
COMMENT ON COLUMN user_credit_account.user_id    IS '所属用户：与 user_account.id 一对一；通过部分唯一索引保证';
COMMENT ON COLUMN user_credit_account.balance    IS '可用余额：DECIMAL(20,4) 精确存储；CHECK 保证非负；变更必须经 CreditService 的原子 SQL';
COMMENT ON COLUMN user_credit_account.created_at IS '账户创建时间';
COMMENT ON COLUMN user_credit_account.updated_at IS '最后更新时间（每次余额调整后更新）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_account_user ON user_credit_account (user_id);
CREATE INDEX        IF NOT EXISTS idx_credit_account_tenant ON user_credit_account (tenant_id);

-- ------------------------------------------------------------
-- 三、credit_transaction 表（不可篡改流水）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS credit_transaction (
    id              BIGSERIAL      PRIMARY KEY,
    tenant_id       BIGINT         NOT NULL REFERENCES tenant (id),
    user_id         BIGINT         NOT NULL REFERENCES user_account (id),
    direction       VARCHAR(16)    NOT NULL,
    delta           DECIMAL(20, 4) NOT NULL,
    balance_before  DECIMAL(20, 4) NOT NULL,
    balance_after   DECIMAL(20, 4) NOT NULL,
    reason          VARCHAR(256)   NOT NULL,
    operator_id     BIGINT         NOT NULL REFERENCES user_account (id),
    operator_name   VARCHAR(128),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_credit_txn_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_credit_txn_delta_positive CHECK (delta > 0)
);

COMMENT ON TABLE  credit_transaction                 IS '额度流水：每次余额调整生成一条不可修改的审计记录；后端不提供 UPDATE/DELETE 接口';
COMMENT ON COLUMN credit_transaction.id              IS '主键，自增序列';
COMMENT ON COLUMN credit_transaction.tenant_id       IS '所属租户：来自被调整用户的租户归属，跨租户隔离';
COMMENT ON COLUMN credit_transaction.user_id         IS '被调整的目标用户';
COMMENT ON COLUMN credit_transaction.direction       IS '操作类型：CREDIT 增加 / DEBIT 扣减；CHECK 限制只能取这两个值';
COMMENT ON COLUMN credit_transaction.delta           IS '变更金额：始终为正数（方向由 direction 表达），CHECK 限制 > 0';
COMMENT ON COLUMN credit_transaction.balance_before  IS '变更前余额：由原子 UPDATE…RETURNING 在同一 SQL 中算得，保证审计连贯';
COMMENT ON COLUMN credit_transaction.balance_after   IS '变更后余额：来自同一 RETURNING 语句';
COMMENT ON COLUMN credit_transaction.reason          IS '操作原因：管理员调整时必填；记录用户输入的中文说明';
COMMENT ON COLUMN credit_transaction.operator_id     IS '操作人 ID：执行调整的用户（管理员或未来网关账户）';
COMMENT ON COLUMN credit_transaction.operator_name   IS '操作人名称（反规范化）：写入时快照，避免未来用户更名后审计错乱';
COMMENT ON COLUMN credit_transaction.created_at      IS '流水写入时间（即调整发生时间）';

-- 高频查询：按用户/租户的时间倒序翻页
CREATE INDEX IF NOT EXISTS idx_credit_txn_user_created   ON credit_transaction (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credit_txn_tenant_created ON credit_transaction (tenant_id, created_at DESC);

-- ------------------------------------------------------------
-- 四、数据回填：为已有 TENANT 用户幂等创建额度账户
-- ------------------------------------------------------------

INSERT INTO user_credit_account (tenant_id, user_id, balance)
SELECT u.tenant_id, u.id, 0
FROM user_account u
WHERE u.scope_type = 'TENANT'
  AND u.deleted_at IS NULL
  AND u.tenant_id IS NOT NULL
ON CONFLICT (user_id) DO NOTHING;

-- ------------------------------------------------------------
-- 五、新增 7 个 API Key / 额度细粒度权限码
--     真正的角色差异由 service 层强制；权限码仅作粗粒度网关。
-- ------------------------------------------------------------

INSERT INTO permission (code, name, description) VALUES
    ('API_KEY_SELF_MANAGE',         '管理自己的 API Key', '允许查看、创建、编辑、启停、删除自己的 API Key'),
    ('API_KEY_TENANT_MANAGE',       '管理本租户 API Key', '允许租户管理员管理本租户全部用户的 API Key'),
    ('API_KEY_CROSS_TENANT_MANAGE', '跨租户管理 API Key', '允许平台管理员跨租户查询与管理所有 API Key'),
    ('CREDIT_SELF_READ',            '查看自己的额度',     '允许查看自己的额度账户与流水'),
    ('CREDIT_TENANT_READ',          '查看本租户额度',     '允许查看本租户全部用户的额度账户与流水'),
    ('CREDIT_TENANT_ADJUST',        '调整本租户额度',     '允许为本租户用户增加或扣减额度'),
    ('CREDIT_CROSS_TENANT_ADJUST',  '跨租户调整额度',     '允许平台管理员跨租户查看与调整用户额度')
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------
-- 六、角色 ↔ 权限绑定
--   PLATFORM_ADMIN：跨租户管理 Key + 跨租户额度（不持有自己的 Key/额度）
--   TENANT_ADMIN：自己 Key + 本租户 Key + 自己额度 + 本租户额度（读+写）
--   TENANT_MEMBER：自己 Key + 自己额度
-- ------------------------------------------------------------

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'PLATFORM_ADMIN'
  AND p.code IN ('API_KEY_CROSS_TENANT_MANAGE', 'CREDIT_CROSS_TENANT_ADJUST')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'TENANT_ADMIN'
  AND p.code IN (
    'API_KEY_SELF_MANAGE',
    'API_KEY_TENANT_MANAGE',
    'CREDIT_SELF_READ',
    'CREDIT_TENANT_READ',
    'CREDIT_TENANT_ADJUST'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'TENANT_MEMBER'
  AND p.code IN ('API_KEY_SELF_MANAGE', 'CREDIT_SELF_READ')
ON CONFLICT (role_id, permission_id) DO NOTHING;
