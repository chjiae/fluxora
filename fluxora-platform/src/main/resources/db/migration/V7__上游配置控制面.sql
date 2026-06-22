-- 上游配置控制面：厂商、接入基础地址、租户通道与加密凭证。
-- 本迁移只建立控制面配置，不包含模型、路由、网关同步或真实上游调用。

CREATE TABLE provider (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    tenant_id BIGINT NULL REFERENCES tenant(id),
    description VARCHAR(500) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_provider_scope CHECK (scope_type IN ('PLATFORM_SHARED', 'TENANT_PRIVATE')),
    CONSTRAINT chk_provider_scope_tenant CHECK ((scope_type = 'PLATFORM_SHARED' AND tenant_id IS NULL) OR (scope_type = 'TENANT_PRIVATE' AND tenant_id IS NOT NULL))
);
COMMENT ON TABLE provider IS '上游厂商：平台共享或指定租户私有的服务来源，不代表具体接入地址或路由通道';
COMMENT ON COLUMN provider.scope_type IS '来源范围：PLATFORM_SHARED 供全部租户选用，TENANT_PRIVATE 只归属一个租户';
COMMENT ON COLUMN provider.tenant_id IS '私有上游所属租户；共享上游必须为空，避免模糊作用域';
COMMENT ON COLUMN provider.deleted_at IS '逻辑删除时间；非空资源不可见且不参与唯一性约束';
CREATE UNIQUE INDEX uk_provider_code_active ON provider (code) WHERE deleted_at IS NULL;
CREATE INDEX idx_provider_tenant_active ON provider (tenant_id, enabled) WHERE deleted_at IS NULL;

CREATE TABLE provider_base_url (
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES provider(id),
    protocol VARCHAR(32) NOT NULL,
    original_base_url VARCHAR(1024) NOT NULL,
    normalized_base_url VARCHAR(1024) NOT NULL,
    display_name VARCHAR(128) NULL,
    remark VARCHAR(500) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_provider_base_url_protocol CHECK (protocol IN ('OPENAI', 'ANTHROPIC'))
);
COMMENT ON TABLE provider_base_url IS '上游逻辑接入基础地址：绑定一个厂商与一种协议，后续网关据此拼接业务接口路径';
COMMENT ON COLUMN provider_base_url.original_base_url IS '用户填写的接入基础 URL，仅允许协议、域名和公共路径';
COMMENT ON COLUMN provider_base_url.normalized_base_url IS '规范化 URL：去末尾斜杠、无 query/fragment，用于同协议唯一判断';
COMMENT ON COLUMN provider_base_url.protocol IS '上游协议：当前 OPENAI 或 ANTHROPIC；同一物理 URL 可按不同协议并存';
CREATE UNIQUE INDEX uk_provider_base_url_active ON provider_base_url (provider_id, protocol, normalized_base_url) WHERE deleted_at IS NULL;
CREATE INDEX idx_provider_base_url_provider_status ON provider_base_url (provider_id, enabled) WHERE deleted_at IS NULL;

CREATE TABLE provider_channel (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    provider_base_url_id BIGINT NOT NULL REFERENCES provider_base_url(id),
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    connect_timeout_ms INTEGER NOT NULL DEFAULT 5000,
    read_timeout_ms INTEGER NOT NULL DEFAULT 60000,
    remark VARCHAR(500) NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_provider_channel_priority CHECK (priority BETWEEN 0 AND 100000),
    CONSTRAINT chk_provider_channel_weight CHECK (weight BETWEEN 1 AND 100000),
    CONSTRAINT chk_provider_channel_connect_timeout CHECK (connect_timeout_ms BETWEEN 100 AND 120000),
    CONSTRAINT chk_provider_channel_read_timeout CHECK (read_timeout_ms BETWEEN 100 AND 600000)
);
COMMENT ON TABLE provider_channel IS '租户实际可选用的上游通道：引用一个可见的接入基础地址并保存运行参数';
COMMENT ON COLUMN provider_channel.tenant_id IS '通道归属租户；租户管理员只能管理当前租户通道';
COMMENT ON COLUMN provider_channel.provider_base_url_id IS '引用的逻辑接入地址；创建时服务层校验共享或本租户私有可见性';
COMMENT ON COLUMN provider_channel.priority IS '未来路由优先级；当前仅保存配置，不执行调度';
COMMENT ON COLUMN provider_channel.weight IS '未来同优先级分流权重；当前仅保存配置';
CREATE INDEX idx_provider_channel_tenant_status ON provider_channel (tenant_id, enabled) WHERE deleted_at IS NULL;
CREATE INDEX idx_provider_channel_base_url_status ON provider_channel (provider_base_url_id, enabled) WHERE deleted_at IS NULL;

CREATE TABLE provider_credential (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    provider_channel_id BIGINT NOT NULL REFERENCES provider_channel(id),
    name VARCHAR(128) NOT NULL,
    credential_type VARCHAR(32) NOT NULL DEFAULT 'API_KEY',
    masked_value VARCHAR(128) NOT NULL,
    credential_fingerprint VARCHAR(64) NOT NULL,
    ciphertext TEXT NOT NULL,
    initialization_vector VARCHAR(64) NOT NULL,
    encryption_version VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 100,
    weight INTEGER NOT NULL DEFAULT 100,
    remark VARCHAR(500) NULL,
    deleted_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_provider_credential_type CHECK (credential_type = 'API_KEY'),
    CONSTRAINT chk_provider_credential_priority CHECK (priority BETWEEN 0 AND 100000),
    CONSTRAINT chk_provider_credential_weight CHECK (weight BETWEEN 1 AND 100000)
);
COMMENT ON TABLE provider_credential IS '通道上游访问凭证：明文不可回显，密文可供未来网关内部解密调用';
COMMENT ON COLUMN provider_credential.masked_value IS '仅供列表、详情和导入结果展示的脱敏标识，不含完整凭证';
COMMENT ON COLUMN provider_credential.credential_fingerprint IS 'HMAC-SHA-256 去重指纹；仅用于当前租户未删除凭证的重复判断，不对外返回';
COMMENT ON COLUMN provider_credential.ciphertext IS 'AES-256-GCM 加密密文；严禁写入 DTO、日志或前端状态';
COMMENT ON COLUMN provider_credential.initialization_vector IS 'AES-GCM 随机向量；仅内部解密使用，严禁对外返回';
COMMENT ON COLUMN provider_credential.encryption_version IS '加密版本标识：为未来密钥轮换预留，不暴露给普通调用方';
COMMENT ON COLUMN provider_credential.deleted_at IS '逻辑删除时间；已删除凭证不参与同租户重复判断，可重新导入';
CREATE UNIQUE INDEX uk_provider_credential_tenant_fingerprint_active ON provider_credential (tenant_id, credential_fingerprint) WHERE deleted_at IS NULL;
CREATE INDEX idx_provider_credential_channel_status ON provider_credential (provider_channel_id, enabled) WHERE deleted_at IS NULL;
CREATE INDEX idx_provider_credential_tenant_status ON provider_credential (tenant_id, enabled) WHERE deleted_at IS NULL;

-- 上游配置细粒度权限：平台管理员跨租户管理，租户管理员仅管理本租户。
INSERT INTO permission (code, name, description) VALUES
    ('UPSTREAM_READ', '查看上游配置', '允许查看可见上游厂商、接入地址、通道和脱敏凭证元数据'),
    ('UPSTREAM_CREATE', '创建上游配置', '允许创建私有厂商、接入地址、通道和凭证'),
    ('UPSTREAM_UPDATE', '编辑上游配置', '允许编辑可管理资源的基础资料和运行参数'),
    ('UPSTREAM_ENABLE', '启用上游配置', '允许启用停用资源'),
    ('UPSTREAM_DISABLE', '停用上游配置', '允许停用资源'),
    ('UPSTREAM_DELETE', '删除上游配置', '允许逻辑删除未受引用保护的资源'),
    ('UPSTREAM_CROSS_TENANT_MANAGE', '跨租户管理上游配置', '允许平台管理员查看和管理全部租户上游配置')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'PLATFORM_ADMIN'
  AND p.code IN ('UPSTREAM_READ','UPSTREAM_CREATE','UPSTREAM_UPDATE','UPSTREAM_ENABLE','UPSTREAM_DISABLE','UPSTREAM_DELETE','UPSTREAM_CROSS_TENANT_MANAGE')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'TENANT_ADMIN'
  AND p.code IN ('UPSTREAM_READ','UPSTREAM_CREATE','UPSTREAM_UPDATE','UPSTREAM_ENABLE','UPSTREAM_DISABLE','UPSTREAM_DELETE')
ON CONFLICT (role_id, permission_id) DO NOTHING;
