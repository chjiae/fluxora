-- Fluxora V8：上游模型候选、平台模型目录、租户发布、控制面路由与价格版本。
-- 本迁移只扩展控制面；不执行真实上游转发、网关同步、扣费或余额冻结。

-- 既有账务资产安全归属到当前唯一结算币种 CNY；保留字段而非重建历史数据，便于未来多币种钱包扩展。
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS settlement_currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE user_credit_account ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE credit_transaction ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE recharge_card_batch ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE recharge_card ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY';
COMMENT ON COLUMN tenant.settlement_currency_code IS '租户当前结算币种；本期固定 CNY，为未来多币种结算预留稳定归属';
COMMENT ON COLUMN user_credit_account.currency_code IS '余额账户币种；历史账户兼容回填为 CNY，余额与币种不可混算';
COMMENT ON COLUMN credit_transaction.currency_code IS '不可篡改余额流水的记账币种；历史流水兼容回填为 CNY';
COMMENT ON COLUMN recharge_card_batch.currency_code IS '卡密批次面额币种；本期固定 CNY';
COMMENT ON COLUMN recharge_card.currency_code IS '单张卡密面额币种；必须与所属批次一致';

-- 某一租户通道实际发现或手工维护的上游模型候选；名称相同也保持独立记录。
CREATE TABLE provider_channel_model (
 id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenant(id), provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id),
 upstream_model_id VARCHAR(256) NOT NULL, display_name VARCHAR(256) NOT NULL, source_type VARCHAR(32) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, source_credential_id BIGINT NULL REFERENCES provider_credential(id), platform_model_id BIGINT NULL,
 last_synced_at TIMESTAMPTZ NULL, last_sync_summary VARCHAR(500) NULL, deleted_at TIMESTAMPTZ NULL,
 created_by BIGINT NULL REFERENCES user_account(id), updated_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_channel_model_source CHECK (source_type IN ('SYNCED','MANUAL'))
);
COMMENT ON TABLE provider_channel_model IS '通道上游模型候选：必须归属具体租户通道，名称相同也不得自动合并';
COMMENT ON COLUMN provider_channel_model.tenant_id IS '候选模型租户归属；服务层与通道归属双重校验，禁止跨租户引用';
COMMENT ON COLUMN provider_channel_model.upstream_model_id IS '向具体上游传递的模型标识；路由目标只能从此字段快照，不接受前端自由输入';
COMMENT ON COLUMN provider_channel_model.source_type IS '候选来源：SYNCED 自动发现或 MANUAL 手工维护';
COMMENT ON COLUMN provider_channel_model.source_credential_id IS '本次同步来源凭证，仅内部审计，绝不返回密文或明文';
COMMENT ON COLUMN provider_channel_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_channel_model_active ON provider_channel_model(provider_channel_id, upstream_model_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_channel_model_tenant_active ON provider_channel_model(tenant_id, provider_channel_id, enabled) WHERE deleted_at IS NULL;

-- 平台统一目录，不保存任何上游通道、凭证或上游模型名。
CREATE TABLE platform_model (
 id BIGSERIAL PRIMARY KEY, code VARCHAR(128) NOT NULL, display_name VARCHAR(256) NOT NULL, description VARCHAR(1000) NULL,
 model_type VARCHAR(64) NULL, tags VARCHAR(500) NULL, supports_streaming BOOLEAN NOT NULL DEFAULT FALSE,
 supports_tools BOOLEAN NOT NULL DEFAULT FALSE, supports_vision BOOLEAN NOT NULL DEFAULT FALSE, supports_cache BOOLEAN NOT NULL DEFAULT FALSE,
 context_length BIGINT NULL, max_output_length BIGINT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, deleted_at TIMESTAMPTZ NULL,
 created_by BIGINT NULL REFERENCES user_account(id), updated_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE provider_channel_model ADD CONSTRAINT fk_channel_model_platform_model FOREIGN KEY(platform_model_id) REFERENCES platform_model(id);
COMMENT ON TABLE platform_model IS '平台统一对外模型目录：模型编码全局唯一且稳定，不保存上游通道、凭证或上游模型名';
COMMENT ON COLUMN platform_model.code IS '稳定的全局对外模型编码；发布后不应随意修改';
COMMENT ON COLUMN platform_model.supports_cache IS '是否支持缓存能力；决定缓存读写价格是否必须完整配置';
COMMENT ON COLUMN platform_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_platform_model_code_active ON platform_model(code) WHERE deleted_at IS NULL;
CREATE INDEX idx_platform_model_enabled_active ON platform_model(enabled) WHERE deleted_at IS NULL;

-- 平台建议价历史：新增版本而非覆盖；金额均为每一百万 Token 的 CNY 精确十进制值。
CREATE TABLE platform_model_price (
 id BIGSERIAL PRIMARY KEY, platform_model_id BIGINT NOT NULL REFERENCES platform_model(id), currency_code VARCHAR(3) NOT NULL,
 input_price NUMERIC(20,4) NOT NULL, output_price NUMERIC(20,4) NOT NULL, cache_write_price NUMERIC(20,4) NULL, cache_read_price NUMERIC(20,4) NULL,
 effective_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ NULL, created_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_platform_price_nonnegative CHECK (input_price >= 0 AND output_price >= 0 AND (cache_write_price IS NULL OR cache_write_price >= 0) AND (cache_read_price IS NULL OR cache_read_price >= 0))
);
COMMENT ON TABLE platform_model_price IS '平台模型默认建议价格历史：金额为每一百万 Token，永不原地覆盖';
COMMENT ON COLUMN platform_model_price.currency_code IS '价格币种；当前只允许 CNY，字段为未来多币种价格历史预留';
COMMENT ON COLUMN platform_model_price.expires_at IS '价格失效时刻；NULL 表示当前有效版本';
CREATE UNIQUE INDEX uk_platform_model_price_current ON platform_model_price(platform_model_id) WHERE expires_at IS NULL;

-- 租户将平台模型发布为自己的对外模型；同租户同平台模型只允许一个未删除记录。
CREATE TABLE tenant_model (
 id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenant(id), platform_model_id BIGINT NOT NULL REFERENCES platform_model(id),
 display_name VARCHAR(256) NULL, description VARCHAR(1000) NULL, publish_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT', price_mode VARCHAR(48) NOT NULL DEFAULT 'INHERIT_PLATFORM_DEFAULT',
 deleted_at TIMESTAMPTZ NULL, created_by BIGINT NULL REFERENCES user_account(id), updated_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_tenant_model_status CHECK (publish_status IN ('DRAFT','ENABLED','DISABLED')),
 CONSTRAINT chk_tenant_model_price_mode CHECK (price_mode IN ('INHERIT_PLATFORM_DEFAULT','TENANT_CUSTOM'))
);
COMMENT ON TABLE tenant_model IS '租户对外发布模型：引用一个平台模型，路由和实际售价均归属此记录';
COMMENT ON COLUMN tenant_model.publish_status IS '发布状态：DRAFT 未满足发布条件，ENABLED 可对用户展示，DISABLED 暂停展示';
COMMENT ON COLUMN tenant_model.price_mode IS '价格模式：继承当前平台默认价或使用租户自定义价格历史';
COMMENT ON COLUMN tenant_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_tenant_model_active ON tenant_model(tenant_id, platform_model_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tenant_model_visible ON tenant_model(tenant_id, publish_status) WHERE deleted_at IS NULL;

CREATE TABLE tenant_model_price (
 id BIGSERIAL PRIMARY KEY, tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id), currency_code VARCHAR(3) NOT NULL,
 input_price NUMERIC(20,4) NOT NULL, output_price NUMERIC(20,4) NOT NULL, cache_write_price NUMERIC(20,4) NULL, cache_read_price NUMERIC(20,4) NULL,
 effective_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), expires_at TIMESTAMPTZ NULL, source_type VARCHAR(32) NOT NULL DEFAULT 'TENANT_CUSTOM', created_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_tenant_price_source CHECK(source_type='TENANT_CUSTOM'),
 CONSTRAINT chk_tenant_price_nonnegative CHECK (input_price >= 0 AND output_price >= 0 AND (cache_write_price IS NULL OR cache_write_price >= 0) AND (cache_read_price IS NULL OR cache_read_price >= 0))
);
COMMENT ON TABLE tenant_model_price IS '租户自定义对外售价历史：金额为每一百万 Token，新增版本而非覆盖';
COMMENT ON COLUMN tenant_model_price.source_type IS '价格来源；本表只保存 TENANT_CUSTOM，继承价实时读取平台当前版本';
CREATE UNIQUE INDEX uk_tenant_model_price_current ON tenant_model_price(tenant_model_id) WHERE expires_at IS NULL;

CREATE TABLE model_route (
 id BIGSERIAL PRIMARY KEY, tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id), inbound_protocol VARCHAR(32) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, remark VARCHAR(500) NULL,
 deleted_at TIMESTAMPTZ NULL, created_by BIGINT NULL REFERENCES user_account(id), updated_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_model_route_protocol CHECK(inbound_protocol IN ('OPENAI','ANTHROPIC'))
);
COMMENT ON TABLE model_route IS '租户模型入站协议路由定义；同一模型同一协议只允许一条未删除路由，不执行协议转换';
COMMENT ON COLUMN model_route.inbound_protocol IS '入站协议；目标通道协议必须完全一致';
COMMENT ON COLUMN model_route.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_model_route_active ON model_route(tenant_model_id,inbound_protocol) WHERE deleted_at IS NULL;

CREATE TABLE route_target (
 id BIGSERIAL PRIMARY KEY, model_route_id BIGINT NOT NULL REFERENCES model_route(id), provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id), provider_channel_model_id BIGINT NOT NULL REFERENCES provider_channel_model(id),
 upstream_model_id_snapshot VARCHAR(256) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, priority INTEGER NOT NULL DEFAULT 100, weight INTEGER NOT NULL DEFAULT 100, remark VARCHAR(500) NULL,
 deleted_at TIMESTAMPTZ NULL, created_by BIGINT NULL REFERENCES user_account(id), updated_by BIGINT NULL REFERENCES user_account(id), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CONSTRAINT chk_route_target_priority CHECK(priority BETWEEN 0 AND 100000), CONSTRAINT chk_route_target_weight CHECK(weight BETWEEN 1 AND 100000)
);
COMMENT ON TABLE route_target IS '最终控制面路由目标：绑定租户通道与该通道候选模型，保存上游标识快照但不执行真实分流';
COMMENT ON COLUMN route_target.provider_channel_model_id IS '必须属于 provider_channel_id 且同租户；服务层验证以阻止跨租户或任意字符串映射';
COMMENT ON COLUMN route_target.upstream_model_id_snapshot IS '创建时从候选模型复制的上游模型名，供未来路由审计与配置快照使用';
COMMENT ON COLUMN route_target.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_route_target_active ON route_target(model_route_id,provider_channel_model_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_route_target_route_enabled ON route_target(model_route_id,enabled) WHERE deleted_at IS NULL;

-- 模型控制面细粒度权限；普通成员仅拥有公开目录读取权限。
INSERT INTO permission(code,name,description) VALUES
 ('MODEL_CATALOG_READ','查看模型目录','查看当前租户已发布模型目录'),('MODEL_CATALOG_MANAGE','管理本租户模型','发布模型并管理本租户路由与售价'),
 ('MODEL_PLATFORM_MANAGE','管理平台模型库','管理平台模型、能力信息与默认价格'),('MODEL_CROSS_TENANT_MANAGE','跨租户管理模型','平台管理员查看和管理全部租户模型配置') ON CONFLICT(code) DO NOTHING;
INSERT INTO role_permission(role_id,permission_id) SELECT r.id,p.id FROM role r,permission p WHERE r.code='PLATFORM_ADMIN' AND p.code IN ('MODEL_CATALOG_READ','MODEL_CATALOG_MANAGE','MODEL_PLATFORM_MANAGE','MODEL_CROSS_TENANT_MANAGE') ON CONFLICT DO NOTHING;
INSERT INTO role_permission(role_id,permission_id) SELECT r.id,p.id FROM role r,permission p WHERE r.code='TENANT_ADMIN' AND p.code IN ('MODEL_CATALOG_READ','MODEL_CATALOG_MANAGE') ON CONFLICT DO NOTHING;
INSERT INTO role_permission(role_id,permission_id) SELECT r.id,p.id FROM role r,permission p WHERE r.code='TENANT_MEMBER' AND p.code='MODEL_CATALOG_READ' ON CONFLICT DO NOTHING;
