-- ============================================================
-- Fluxora V6：卡密批次与卡密
--
-- 本迁移在「用户额度账户 + 额度流水」之上叠加充值卡密体系：
--
-- 1. recharge_card_batch 卡密批次：每一面额组一个批次，便于统计与审计
--    - 字段：tenant_id / batch_code / name / denomination / total_count
--      / status (ENABLED|DISABLED) / expire_at / created_by_id / 审计字段
--    - 批次停用后，未核销卡密无法核销；批次重新启用后符合条件卡密恢复可用
--    - 已核销卡密不可恢复（在 service + DB CHECK 双层保障）
--
-- 2. recharge_card 单张卡密：明文绝不入库，仅存安全哈希与公开前缀
--    - 字段：tenant_id / batch_id / card_prefix / card_hash / denomination
--      / status (ENABLED|DISABLED|REDEEMED|EXPIRED) / expire_at
--      / redeemed_user_id / redeemed_at / disabled_reason / 审计字段
--    - card_hash 全局 UNIQUE，杜绝跨租户/跨批次碰撞
--    - 状态由 service 转换；REDEEMED / EXPIRED 为终态，CHECK 约束限制取值
--
-- 3. credit_transaction 表扩展：新增 source（区分手工调整与卡密充值）
--    与 card_id（引用卡密）；同时为 card_id 添加部分唯一索引，
--    DB 层保证一张卡密最多生成一条 CARD_REDEEM 流水（防重复入账）
--
-- 4. 权限：新增 4 个细粒度权限码，绑定到既有角色
--
-- 所有 DDL / INSERT 设计为幂等（IF EXISTS / IF NOT EXISTS / ON CONFLICT），
-- 重复执行不破坏现有数据。
-- ============================================================

-- ------------------------------------------------------------
-- 一、recharge_card_batch 卡密批次
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS recharge_card_batch (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenant (id),
    batch_code      VARCHAR(32)   NOT NULL,
    name            VARCHAR(128),
    denomination    DECIMAL(20, 4) NOT NULL,
    total_count     INT           NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    expire_at       TIMESTAMPTZ,
    created_by_id   BIGINT        NOT NULL REFERENCES user_account (id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_batch_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT chk_batch_denomination_positive CHECK (denomination > 0),
    CONSTRAINT chk_batch_total_positive CHECK (total_count > 0)
);

COMMENT ON TABLE  recharge_card_batch                IS '卡密批次：一次发卡操作的一个面额组；批次粒度便于运营统计与审计';
COMMENT ON COLUMN recharge_card_batch.id             IS '主键，自增序列';
COMMENT ON COLUMN recharge_card_batch.tenant_id      IS '所属租户：批次归属租户，跨租户隔离的根';
COMMENT ON COLUMN recharge_card_batch.batch_code     IS '业务编号：RCB-yyyymmdd-XXXX 形式，全局唯一，便于审计';
COMMENT ON COLUMN recharge_card_batch.name           IS '批次备注：可选，便于运营理解用途';
COMMENT ON COLUMN recharge_card_batch.denomination   IS '面额：DECIMAL(20,4) 与用户额度账户精度一致';
COMMENT ON COLUMN recharge_card_batch.total_count    IS '生成总张数：受 fluxora.security.card.batch-max-count 上限约束';
COMMENT ON COLUMN recharge_card_batch.status         IS '批次状态：ENABLED 可用 / DISABLED 整批暂停核销';
COMMENT ON COLUMN recharge_card_batch.expire_at      IS '批次统一过期时间：超过后批内所有卡密视为 EXPIRED；NULL 表示永不过期';
COMMENT ON COLUMN recharge_card_batch.created_by_id  IS '创建人：审计字段，记录发卡操作者';
COMMENT ON COLUMN recharge_card_batch.created_at     IS '创建时间';
COMMENT ON COLUMN recharge_card_batch.updated_at     IS '最后更新时间';

CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_code ON recharge_card_batch (batch_code);
CREATE INDEX IF NOT EXISTS idx_batch_tenant ON recharge_card_batch (tenant_id, created_at DESC);

-- ------------------------------------------------------------
-- 二、recharge_card 单张卡密
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS recharge_card (
    id                 BIGSERIAL     PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenant (id),
    batch_id           BIGINT        NOT NULL REFERENCES recharge_card_batch (id),
    card_prefix        VARCHAR(20)   NOT NULL,
    card_hash          CHAR(64)      NOT NULL,
    denomination       DECIMAL(20, 4) NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    expire_at          TIMESTAMPTZ,
    redeemed_user_id   BIGINT        REFERENCES user_account (id),
    redeemed_at        TIMESTAMPTZ,
    disabled_reason    VARCHAR(256),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_card_status CHECK (status IN ('ENABLED', 'DISABLED', 'REDEEMED', 'EXPIRED'))
);

COMMENT ON TABLE  recharge_card                    IS '单张卡密：HMAC-SHA256 哈希存储，绝不保存明文；明文仅在批次创建响应中返回一次';
COMMENT ON COLUMN recharge_card.id                 IS '主键，自增序列';
COMMENT ON COLUMN recharge_card.tenant_id          IS '所属租户：与 batch.tenant_id 冗余但便于索引剪枝';
COMMENT ON COLUMN recharge_card.batch_id           IS '所属批次';
COMMENT ON COLUMN recharge_card.card_prefix        IS '公开前缀：形如 FLX-XXXX，用于脱敏列表展示';
COMMENT ON COLUMN recharge_card.card_hash          IS 'HMAC-SHA256(规范化明文, server_pepper) 的 hex；网关核销时用其校验输入';
COMMENT ON COLUMN recharge_card.denomination       IS '面额：冗余自 batch，便于核销时无需 join';
COMMENT ON COLUMN recharge_card.status             IS '状态：ENABLED 可核销 / DISABLED 已停用 / REDEEMED 已核销终态 / EXPIRED 已过期';
COMMENT ON COLUMN recharge_card.expire_at          IS '过期时间：冗余自 batch，便于核销时一次性 SQL 条件判断';
COMMENT ON COLUMN recharge_card.redeemed_user_id   IS '核销用户：仅 REDEEMED 状态有值';
COMMENT ON COLUMN recharge_card.redeemed_at        IS '核销时间：与状态 REDEEMED 一一对应';
COMMENT ON COLUMN recharge_card.disabled_reason    IS '停用原因：可选，便于运营审计';
COMMENT ON COLUMN recharge_card.created_at         IS '创建时间';
COMMENT ON COLUMN recharge_card.updated_at         IS '最后更新时间';

-- 全局防碰撞唯一约束（即使跨租户/跨批次也禁止两张哈希相同的卡密）
CREATE UNIQUE INDEX IF NOT EXISTS uk_card_hash ON recharge_card (card_hash);
CREATE INDEX IF NOT EXISTS idx_card_batch ON recharge_card (batch_id, status);
CREATE INDEX IF NOT EXISTS idx_card_tenant_status ON recharge_card (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_card_redeemed_user ON recharge_card (redeemed_user_id)
    WHERE redeemed_user_id IS NOT NULL;

-- ------------------------------------------------------------
-- 三、credit_transaction 表扩展：source + card_id
-- ------------------------------------------------------------

-- source：区分手工调整 vs 卡密充值；默认 MANUAL_ADJUSTMENT，兼容已有记录
ALTER TABLE credit_transaction
    ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'MANUAL_ADJUSTMENT';
ALTER TABLE credit_transaction
    ADD COLUMN IF NOT EXISTS card_id BIGINT REFERENCES recharge_card (id);

-- CHECK 限制 source 取值；幂等加约束需用 DO 块判断
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.check_constraints
        WHERE constraint_name = 'chk_credit_txn_source'
    ) THEN
        ALTER TABLE credit_transaction
            ADD CONSTRAINT chk_credit_txn_source
            CHECK (source IN ('MANUAL_ADJUSTMENT', 'CARD_REDEEM'));
    END IF;
END $$;

COMMENT ON COLUMN credit_transaction.source  IS '流水来源：MANUAL_ADJUSTMENT 管理员手工调整 / CARD_REDEEM 卡密充值；CHECK 限制取值';
COMMENT ON COLUMN credit_transaction.card_id IS '关联卡密 ID：仅 source=CARD_REDEEM 有值；通过下方部分唯一索引防止同一卡密重复入账';

-- 同一张卡密最多一条 CARD_REDEEM 流水（DB 层防重复入账）
CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_txn_card
    ON credit_transaction (card_id) WHERE source = 'CARD_REDEEM';

-- ------------------------------------------------------------
-- 四、权限种子（4 个卡密细粒度权限）
-- ------------------------------------------------------------

INSERT INTO permission (code, name, description) VALUES
    ('CARD_SELF_REDEEM',          '核销卡密充值',     '允许租户用户核销卡密并增加自己的额度'),
    ('CARD_TENANT_MANAGE',        '管理本租户卡密',   '允许租户管理员创建批次、查看卡密、停用启用'),
    ('CARD_CROSS_TENANT_MANAGE',  '跨租户管理卡密',   '允许平台管理员跨租户管理卡密批次'),
    ('CARD_RECORD_READ_TENANT',   '查看本租户充值流水', '允许查看本租户的卡密充值记录')
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------
-- 五、角色 ↔ 权限绑定
-- ------------------------------------------------------------

-- PLATFORM_ADMIN：跨租户管理卡密 + 跨租户充值流水
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'PLATFORM_ADMIN'
  AND p.code IN ('CARD_CROSS_TENANT_MANAGE', 'CARD_RECORD_READ_TENANT')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- TENANT_ADMIN：本租户全套（含核销 + 管理 + 查流水）
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'TENANT_ADMIN'
  AND p.code IN ('CARD_SELF_REDEEM', 'CARD_TENANT_MANAGE', 'CARD_RECORD_READ_TENANT')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- TENANT_MEMBER：仅核销自己
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'TENANT_MEMBER'
  AND p.code = 'CARD_SELF_REDEEM'
ON CONFLICT (role_id, permission_id) DO NOTHING;
