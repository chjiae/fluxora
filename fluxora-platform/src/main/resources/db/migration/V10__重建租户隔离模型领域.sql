-- Fluxora V10：以租户为唯一边界重建模型领域。
-- 本迁移完成两件事：
--   1) 移除全局模型目录（platform_model / platform_model_price）及与之耦合的旧候选映射、旧租户模型、旧路由与路由目标；
--   2) 重建六张以 tenant_id 为最高维度的模型相关表：provider_channel_model、tenant_model、tenant_model_price、
--      tenant_model_candidate_mapping、model_route、route_target，并注入 TENANT_MODEL_* 系列权限。
-- 共享 Provider / ProviderBaseUrl 不在本迁移变更范围之内：共享仅停留在「厂商定义 + 接入基础地址」两层，
-- 自 provider_channel 起所有资源继续按租户隔离；本迁移确保后续任何模型、候选、价格、映射、路由
-- 都强绑定 tenant_id 并通过部分唯一索引与 CHECK 约束在数据库层兜底。
-- 本迁移不实现 Redis 快照、网关下发、SSE 转发、Token 计费或扣费。

-- 1. 清理旧表与旧权限：按外键依赖逆序 DROP，避免遗留 platform_model_id 列与旧候选关系。
DROP TABLE IF EXISTS route_target CASCADE;
DROP TABLE IF EXISTS model_route CASCADE;
DROP TABLE IF EXISTS tenant_model_price CASCADE;
DROP TABLE IF EXISTS tenant_model CASCADE;
DROP TABLE IF EXISTS platform_model_price CASCADE;
DROP TABLE IF EXISTS platform_model CASCADE;
DROP TABLE IF EXISTS provider_channel_model CASCADE;

DELETE FROM role_permission WHERE permission_id IN (
    SELECT id FROM permission WHERE code IN (
        'MODEL_CATALOG_READ','MODEL_CATALOG_MANAGE','MODEL_PLATFORM_MANAGE','MODEL_CROSS_TENANT_MANAGE'
    )
);
DELETE FROM permission WHERE code IN (
    'MODEL_CATALOG_READ','MODEL_CATALOG_MANAGE','MODEL_PLATFORM_MANAGE','MODEL_CROSS_TENANT_MANAGE'
);

-- 2. provider_channel_model：某租户某通道下的上游模型候选；不再保存 platform_model_id。
-- 候选不再依赖任何全局模型目录，租户管理员可手工维护，未来开放同步发现仅追加同步元数据。
CREATE TABLE provider_channel_model (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id),
    upstream_model_id VARCHAR(256) NOT NULL,
    upstream_display_name VARCHAR(256) NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    supports_streaming BOOLEAN NOT NULL DEFAULT FALSE,
    supports_tool_calling BOOLEAN NOT NULL DEFAULT FALSE,
    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
    supports_cache BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at TIMESTAMPTZ NULL,
    last_sync_summary VARCHAR(500) NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES user_account(id),
    updated_by BIGINT NULL REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_channel_model_source CHECK (source_type IN ('SYNCED','MANUAL'))
);
COMMENT ON TABLE provider_channel_model IS '租户上游模型候选：必须属于具体租户在具体 provider_channel 下的可用上游模型，不再映射到任何全局模型';
COMMENT ON COLUMN provider_channel_model.tenant_id IS '候选所属租户；服务层强制与 provider_channel.tenant_id 一致，禁止跨租户引用';
COMMENT ON COLUMN provider_channel_model.provider_channel_id IS '所属通道；通道与候选必须同一租户，通道软删除后候选不应继续被映射';
COMMENT ON COLUMN provider_channel_model.upstream_model_id IS '向上游传递的模型标识；路由目标只能通过候选映射间接引用，禁止前端自由输入';
COMMENT ON COLUMN provider_channel_model.source_type IS '候选来源：MANUAL 手工维护，SYNCED 通过同步发现（本轮不实现同步）';
COMMENT ON COLUMN provider_channel_model.supports_streaming IS '上游是否支持流式输出；用于校验租户模型声明能力是否被有效候选支撑';
COMMENT ON COLUMN provider_channel_model.supports_tool_calling IS '上游是否支持工具调用；同上';
COMMENT ON COLUMN provider_channel_model.supports_vision IS '上游是否支持视觉输入；同上';
COMMENT ON COLUMN provider_channel_model.supports_cache IS '上游是否支持缓存命中；同上，且决定缓存读写价格是否必须配置';
COMMENT ON COLUMN provider_channel_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_channel_model_upstream_active
    ON provider_channel_model (provider_channel_id, upstream_model_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_channel_model_tenant_active
    ON provider_channel_model (tenant_id, provider_channel_id, enabled)
    WHERE deleted_at IS NULL;

-- 3. tenant_model：唯一对外模型主体；同租户内 model_code 唯一，跨租户允许重复。
CREATE TABLE tenant_model (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    model_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    description VARCHAR(1000) NULL,
    supports_streaming BOOLEAN NOT NULL DEFAULT FALSE,
    supports_tool_calling BOOLEAN NOT NULL DEFAULT FALSE,
    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
    supports_cache BOOLEAN NOT NULL DEFAULT FALSE,
    publish_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES user_account(id),
    updated_by BIGINT NULL REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tenant_model_status CHECK (publish_status IN ('DRAFT','ENABLED','DISABLED'))
);
COMMENT ON TABLE tenant_model IS '租户对外模型：唯一对 C 端用户售卖、定价、路由与发布的模型实体；不依赖任何全局模型目录';
COMMENT ON COLUMN tenant_model.tenant_id IS '模型所属租户；不同租户允许使用相同 model_code，但能力、价格、候选与路由完全独立';
COMMENT ON COLUMN tenant_model.model_code IS '租户内唯一的对外模型编码；删除后允许同租户重新使用（依赖部分唯一索引）';
COMMENT ON COLUMN tenant_model.publish_status IS '发布状态：DRAFT 未完成配置；ENABLED 对 C 端可见；DISABLED 暂停展示';
COMMENT ON COLUMN tenant_model.enabled IS '冗余启用标记：与 publish_status=ENABLED 同步，用于公开目录索引快速过滤';
COMMENT ON COLUMN tenant_model.supports_streaming IS '租户声明对外支持流式输出，启用前必须有至少一个候选映射支撑';
COMMENT ON COLUMN tenant_model.supports_tool_calling IS '租户声明对外支持工具调用，启用前必须有至少一个候选映射支撑';
COMMENT ON COLUMN tenant_model.supports_vision IS '租户声明对外支持视觉输入，启用前必须有至少一个候选映射支撑';
COMMENT ON COLUMN tenant_model.supports_cache IS '租户声明对外支持缓存命中；启用前必须有候选支撑且四项价格中的缓存读写价格已配置';
COMMENT ON COLUMN tenant_model.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_tenant_model_code_active
    ON tenant_model (tenant_id, model_code)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_tenant_model_visible
    ON tenant_model (tenant_id, publish_status, enabled)
    WHERE deleted_at IS NULL;

-- 4. tenant_model_price：租户独立价格历史，金额每 100 万 Token 计；同一租户模型同一时刻最多一个未失效版本。
CREATE TABLE tenant_model_price (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id),
    currency_code VARCHAR(3) NOT NULL DEFAULT 'CNY',
    input_price_per_million NUMERIC(24,8) NOT NULL,
    output_price_per_million NUMERIC(24,8) NOT NULL,
    cache_write_price_per_million NUMERIC(24,8) NULL,
    cache_read_price_per_million NUMERIC(24,8) NULL,
    version INTEGER NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expired_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tenant_price_currency CHECK (currency_code = 'CNY'),
    CONSTRAINT chk_tenant_price_nonnegative CHECK (
        input_price_per_million >= 0 AND output_price_per_million >= 0
        AND (cache_write_price_per_million IS NULL OR cache_write_price_per_million >= 0)
        AND (cache_read_price_per_million IS NULL OR cache_read_price_per_million >= 0)
    )
);
COMMENT ON TABLE tenant_model_price IS '租户模型对外售价历史：金额为每 100 万 Token 的 CNY 8 位小数原子精度值，新增版本而非覆盖';
COMMENT ON COLUMN tenant_model_price.tenant_id IS '价格所属租户；与 tenant_model.tenant_id 强一致，服务层兜底校验';
COMMENT ON COLUMN tenant_model_price.tenant_model_id IS '价格归属的租户模型；不存在「跨租户复用价格」或「继承平台默认价」';
COMMENT ON COLUMN tenant_model_price.currency_code IS '价格币种；当前固定 CNY，字段保留用于未来多币种价格历史';
COMMENT ON COLUMN tenant_model_price.input_price_per_million IS '输入单价：每 100 万 Token，CNY 8 位小数；不支持 float/double';
COMMENT ON COLUMN tenant_model_price.output_price_per_million IS '输出单价：每 100 万 Token，CNY 8 位小数；不支持 float/double';
COMMENT ON COLUMN tenant_model_price.cache_write_price_per_million IS '缓存写入单价：每 100 万 Token；不支持缓存时为 NULL';
COMMENT ON COLUMN tenant_model_price.cache_read_price_per_million IS '缓存读取单价：每 100 万 Token；不支持缓存时为 NULL';
COMMENT ON COLUMN tenant_model_price.version IS '同一租户模型内的价格版本号；服务层在事务内单调递增';
COMMENT ON COLUMN tenant_model_price.expired_at IS '价格失效时刻；NULL 表示当前有效版本，部分唯一索引兜底每模型仅一个有效价格';
CREATE UNIQUE INDEX uk_tenant_model_price_current
    ON tenant_model_price (tenant_model_id)
    WHERE expired_at IS NULL;
CREATE INDEX idx_tenant_model_price_history
    ON tenant_model_price (tenant_id, tenant_model_id, version);

-- 5. tenant_model_candidate_mapping：租户模型可以使用本租户某个上游候选；不保存优先级、权重或协议。
CREATE TABLE tenant_model_candidate_mapping (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id),
    provider_channel_model_id BIGINT NOT NULL REFERENCES provider_channel_model(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark VARCHAR(500) NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES user_account(id),
    updated_by BIGINT NULL REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE tenant_model_candidate_mapping IS '租户模型与上游候选的允许关系：三方 tenant_id 必须一致，禁止跨租户引用；不保存优先级、权重或协议（属于 RouteTarget）';
COMMENT ON COLUMN tenant_model_candidate_mapping.tenant_id IS '映射所属租户；服务层校验 tenant_model 与 provider_channel_model 均为同一租户';
COMMENT ON COLUMN tenant_model_candidate_mapping.tenant_model_id IS '所映射的对外模型';
COMMENT ON COLUMN tenant_model_candidate_mapping.provider_channel_model_id IS '所映射的上游候选；候选所属通道必须有效，通道与凭证停用时不得作为模型启用支撑';
COMMENT ON COLUMN tenant_model_candidate_mapping.enabled IS '映射启用标记：停用后不再作为 RouteTarget 启用候选源';
COMMENT ON COLUMN tenant_model_candidate_mapping.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻；被 ENABLED RouteTarget 引用的映射不得直接删除';
CREATE UNIQUE INDEX uk_tmcm_pair_active
    ON tenant_model_candidate_mapping (tenant_model_id, provider_channel_model_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_tmcm_tenant_model_active
    ON tenant_model_candidate_mapping (tenant_id, tenant_model_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_tmcm_tenant_candidate_active
    ON tenant_model_candidate_mapping (tenant_id, provider_channel_model_id)
    WHERE deleted_at IS NULL;

-- 6. model_route：租户模型在某一入站协议下的路由定义；同模型同协议未删除唯一。
CREATE TABLE model_route (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    tenant_model_id BIGINT NOT NULL REFERENCES tenant_model(id),
    inbound_protocol VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark VARCHAR(500) NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES user_account(id),
    updated_by BIGINT NULL REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_model_route_protocol CHECK (inbound_protocol IN ('OPENAI','ANTHROPIC'))
);
COMMENT ON TABLE model_route IS '租户模型入站协议路由定义；同模型同协议只允许一条未删除路由，本轮不执行真实协议转换';
COMMENT ON COLUMN model_route.tenant_id IS '路由所属租户；服务层校验与 tenant_model.tenant_id 一致';
COMMENT ON COLUMN model_route.inbound_protocol IS '入站协议：OPENAI / ANTHROPIC；RouteTarget 引用的候选通道协议必须与之兼容';
COMMENT ON COLUMN model_route.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_model_route_active
    ON model_route (tenant_model_id, inbound_protocol)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_model_route_tenant_active
    ON model_route (tenant_id, tenant_model_id)
    WHERE deleted_at IS NULL;

-- 7. route_target：通过候选映射间接绑定上游通道与候选，承载优先级、权重；不直接引用 provider_channel_model_id。
CREATE TABLE route_target (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    model_route_id BIGINT NOT NULL REFERENCES model_route(id),
    tenant_model_candidate_mapping_id BIGINT NOT NULL REFERENCES tenant_model_candidate_mapping(id),
    provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id),
    upstream_model_id_snapshot VARCHAR(256) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    remark VARCHAR(500) NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES user_account(id),
    updated_by BIGINT NULL REFERENCES user_account(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_route_target_priority CHECK (priority BETWEEN 0 AND 100000),
    CONSTRAINT chk_route_target_weight CHECK (weight BETWEEN 1 AND 100000)
);
COMMENT ON TABLE route_target IS '路由目标：以 tenant_model_candidate_mapping 作为事实来源，承载优先级与权重；provider_channel_id 与 upstream_model_id_snapshot 仅为审计冗余';
COMMENT ON COLUMN route_target.tenant_id IS '路由目标所属租户；服务层校验四方一致（route / mapping / 候选 / 通道）';
COMMENT ON COLUMN route_target.model_route_id IS '所属路由；同一路由下不得重复引用同一映射';
COMMENT ON COLUMN route_target.tenant_model_candidate_mapping_id IS '事实来源：合法性校验、能力支撑判定、租户隔离全部以此为准';
COMMENT ON COLUMN route_target.provider_channel_id IS '审计冗余：候选映射对应通道的 id，仅用于查询展示与未来快照；写入与合法性校验仍以映射为准';
COMMENT ON COLUMN route_target.upstream_model_id_snapshot IS '审计冗余：创建时复制的上游模型标识，便于未来运行时快照与排查';
COMMENT ON COLUMN route_target.priority IS '同路由内调度优先级；本轮仅保存配置，不参与真实调度';
COMMENT ON COLUMN route_target.weight IS '同优先级分流权重；本轮仅保存配置，不参与真实调度';
COMMENT ON COLUMN route_target.deleted_at IS 'NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计、未来窗口期恢复与排查';
CREATE UNIQUE INDEX uk_route_target_mapping_active
    ON route_target (model_route_id, tenant_model_candidate_mapping_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_route_target_route_enabled
    ON route_target (model_route_id, enabled)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_route_target_mapping_active
    ON route_target (tenant_model_candidate_mapping_id, enabled)
    WHERE deleted_at IS NULL;

-- 8. 新权限：以租户为最高维度的模型读写、跨租户代管与公开目录读取。
INSERT INTO permission (code, name, description) VALUES
    ('TENANT_MODEL_READ', '查看本租户模型', '查看当前租户的模型、候选映射、价格、路由与路由目标'),
    ('TENANT_MODEL_MANAGE', '管理本租户模型', '创建、编辑、启停、删除当前租户的模型、候选映射、价格、路由与路由目标'),
    ('TENANT_MODEL_CROSS_TENANT_MANAGE', '跨租户管理模型', '平台管理员显式指定目标租户后管理任意租户的模型领域资源'),
    ('TENANT_MODEL_PUBLIC_READ', '查看可用模型目录', '查看当前租户对 C 端可见的模型与对外价格')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'PLATFORM_ADMIN'
  AND p.code IN ('TENANT_MODEL_READ','TENANT_MODEL_MANAGE','TENANT_MODEL_CROSS_TENANT_MANAGE','TENANT_MODEL_PUBLIC_READ')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'TENANT_ADMIN'
  AND p.code IN ('TENANT_MODEL_READ','TENANT_MODEL_MANAGE','TENANT_MODEL_PUBLIC_READ')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'TENANT_MEMBER'
  AND p.code IN ('TENANT_MODEL_PUBLIC_READ')
ON CONFLICT (role_id, permission_id) DO NOTHING;
