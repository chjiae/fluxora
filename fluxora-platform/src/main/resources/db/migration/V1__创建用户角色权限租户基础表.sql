-- ============================================================
-- Fluxora 用户体系、角色权限体系与租户体系基础表
-- 本轮建立六张核心表：租户、用户账号、角色、权限、用户角色关联、角色权限关联
-- 所有业务 SQL 均通过 MyBatis XML 访问，不在注解中编写 SQL
-- ============================================================

-- 租户表：记录平台中所有租户（自营与第三方）
-- 租户码全局唯一，用于业务标识与路由
-- 启用状态与过期时间共同决定租户当期是否可用
-- 逻辑删除标识支持软删除，保护引用完整性
CREATE TABLE tenant
(
    id          BIGSERIAL    PRIMARY KEY,
    tenant_code VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL DEFAULT 'THIRD_PARTY',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    expire_at   TIMESTAMPTZ,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 租户码唯一约束：确保业务标识不重复
CREATE UNIQUE INDEX uk_tenant_tenant_code ON tenant (tenant_code) WHERE is_deleted = FALSE;

-- 用户账号表：记录平台所有用户的登录凭证与归属信息
-- scope_type 区分平台级 (PLATFORM) 与租户级 (TENANT) 用户
-- 租户级用户通过 tenant_id 关联所属租户
-- 密码哈希统一使用 BCrypt 存储
CREATE TABLE user_account
(
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    display_name  VARCHAR(128),
    email         VARCHAR(256),
    scope_type    VARCHAR(32)  NOT NULL,
    tenant_id     BIGINT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 用户名全局唯一约束
CREATE UNIQUE INDEX uk_user_account_username ON user_account (username);

-- 角色表：记录平台与租户级别的角色定义
-- scope_type 与用户账号一致，平台角色不可分配给租户用户，反之亦然
CREATE TABLE role
(
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    scope_type  VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 角色码在同作用域内唯一
CREATE UNIQUE INDEX uk_role_code_scope ON role (code, scope_type);

-- 权限表：记录系统中所有可分配的权限项
-- 权限码为稳定字符串编码，用于后端鉴权判断
CREATE TABLE permission
(
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 权限码全局唯一
CREATE UNIQUE INDEX uk_permission_code ON permission (code);

-- 用户角色关联表：记录用户与角色的多对多关系
CREATE TABLE user_role
(
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES user_account (id),
    role_id    BIGINT      NOT NULL REFERENCES role (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 用户角色唯一约束：同一角色不可重复分配给同一用户
CREATE UNIQUE INDEX uk_user_role ON user_role (user_id, role_id);

-- 角色权限关联表：记录角色与权限的多对多关系
CREATE TABLE role_permission
(
    id            BIGSERIAL   PRIMARY KEY,
    role_id       BIGINT      NOT NULL REFERENCES role (id),
    permission_id BIGINT      NOT NULL REFERENCES permission (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 角色权限唯一约束：同一权限不可重复分配给同一角色
CREATE UNIQUE INDEX uk_role_permission ON role_permission (role_id, permission_id);

-- ============================================================
-- 静态权限数据：系统级权限编码
-- 这些权限码在后端 Security 注解与 XML 中使用
-- ============================================================
INSERT INTO permission (code, name, description) VALUES
    ('PLATFORM_ADMIN', '平台管理', '平台级管理权限，包含租户管理、用户管理等全部操作'),
    ('TENANT_ADMIN', '租户管理', '租户级管理权限，管理所属租户内资源'),
    ('TENANT_MEMBER', '租户成员', '租户内普通成员权限，使用租户内资源');

-- ============================================================
-- 静态角色数据：系统级角色定义
-- 平台管理员为平台级角色，租户管理员与租户成员为租户级角色
-- ============================================================
INSERT INTO role (code, name, description, scope_type) VALUES
    ('PLATFORM_ADMIN', '平台管理员', '平台超级管理员，管理所有租户与平台配置', 'PLATFORM'),
    ('TENANT_ADMIN', '租户管理员', '租户管理员，管理所属租户内用户与资源', 'TENANT'),
    ('TENANT_MEMBER', '租户成员', '租户成员，使用租户内分配的 AI 资源', 'TENANT');

-- ============================================================
-- 角色-权限关联：建立角色与权限的多对多映射
-- 通过子查询定位 role 和 permission 的数据库 ID
-- ============================================================
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r,
     permission p
WHERE r.code = 'PLATFORM_ADMIN'
  AND p.code = 'PLATFORM_ADMIN';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r,
     permission p
WHERE r.code = 'TENANT_ADMIN'
  AND p.code = 'TENANT_ADMIN';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r,
     permission p
WHERE r.code = 'TENANT_MEMBER'
  AND p.code = 'TENANT_MEMBER';
