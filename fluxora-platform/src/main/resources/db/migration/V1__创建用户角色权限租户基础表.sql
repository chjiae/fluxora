-- ============================================================
-- Fluxora V1：用户体系、角色权限体系与租户体系基础表
--
-- 本迁移建立六张核心表，构成多租户、RBAC 权限控制的持久化基础：
--   tenant        — 租户（SELF_OPERATED 自营 / STANDARD 标准）
--   user_account  — 用户账号（PLATFORM 平台级 / TENANT 租户级）
--   role          — 角色定义（作用域 PLATFORM / TENANT 隔离）
--   permission    — 权限项（稳定字符串编码，@PreAuthorize 鉴权用）
--   user_role     — 用户↔角色多对多关联
--   role_permission — 角色↔权限多对多关联
--
-- 关键约束与索引：
--   - tenant.tenant_code 在未被逻辑删除记录中全局唯一
--   - user_account.username 全局唯一
--   - role.code 在同一作用域内唯一
--   - permission.code 全局唯一
--   - 关联表均有唯一复合索引防止重复分配
--
-- 所有业务 SQL 仅通过 MyBatis XML 访问，禁止在 Java 注解中编写 SQL。
-- ============================================================

-- 租户表
-- tenant_code : 业务标识码，全局唯一，创建后不可变更，用于 API 路由和标识
-- type        : SELF_OPERATED（仅初始化流程创建）或 STANDARD（标准租户）
-- enabled     : 启用状态，false 时租户下全部用户无法登录
-- expire_at   : 过期时间，超过后即使 enabled=true 也认定为已过期
-- is_deleted  : 逻辑删除标识，true 表示已删除，不可恢复
CREATE TABLE tenant
(
    id          BIGSERIAL    PRIMARY KEY,
    tenant_code VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL DEFAULT 'STANDARD',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    expire_at   TIMESTAMPTZ,
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE tenant IS '租户表：记录平台中所有租户（自营与标准），租户码全局唯一，支持启用、过期与逻辑删除';
COMMENT ON COLUMN tenant.id IS '主键，自增序列';
COMMENT ON COLUMN tenant.tenant_code IS '租户码，全局唯一，用于业务标识与路由，创建后不可修改';
COMMENT ON COLUMN tenant.name IS '租户名称，对外展示用';
COMMENT ON COLUMN tenant.type IS '租户类型：SELF_OPERATED 自营租户（仅初始化创建）或 STANDARD 标准租户（API 创建）';
COMMENT ON COLUMN tenant.enabled IS '启用状态：false 时租户下所有用户无法登录和访问';
COMMENT ON COLUMN tenant.expire_at IS '过期时间：超过后即使 enabled=true 也视为已过期，NULL 表示永不过期';
COMMENT ON COLUMN tenant.is_deleted IS '逻辑删除标识：true 表示已删除，不可恢复，查询时默认排除';
COMMENT ON COLUMN tenant.created_at IS '创建时间';
COMMENT ON COLUMN tenant.updated_at IS '最后更新时间';

-- 租户码唯一约束：确保未删除记录中业务标识不重复
CREATE UNIQUE INDEX uk_tenant_tenant_code ON tenant (tenant_code) WHERE is_deleted = FALSE;

-- 用户账号表
-- scope_type  : PLATFORM（平台级，如平台管理员）或 TENANT（租户级用户）
-- tenant_id   : 租户级用户关联的租户 ID，平台级用户为 NULL
-- password_hash : BCrypt 哈希，绝不以明文存储或传输
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

COMMENT ON TABLE user_account IS '用户账号表：记录所有用户的登录凭证与归属，支持平台级和租户级双作用域';
COMMENT ON COLUMN user_account.id IS '主键，自增序列';
COMMENT ON COLUMN user_account.username IS '用户名，全局唯一，用于登录认证';
COMMENT ON COLUMN user_account.password_hash IS '密码哈希：BCrypt 加密存储，绝不以明文入库或传输';
COMMENT ON COLUMN user_account.display_name IS '显示名称，用于界面展示';
COMMENT ON COLUMN user_account.email IS '邮箱地址，可选';
COMMENT ON COLUMN user_account.scope_type IS '用户作用域：PLATFORM 平台级用户 / TENANT 租户级用户';
COMMENT ON COLUMN user_account.tenant_id IS '所属租户 ID：仅租户级用户有值，平台级用户为 NULL';
COMMENT ON COLUMN user_account.enabled IS '账号启用状态：false 表示已停用，无法登录';
COMMENT ON COLUMN user_account.created_at IS '创建时间';
COMMENT ON COLUMN user_account.updated_at IS '最后更新时间';

-- 用户名全局唯一约束
CREATE UNIQUE INDEX uk_user_account_username ON user_account (username);

-- 角色表
-- scope_type 与 user_account 一致：平台角色（PLATFORM）不可分配给租户用户，
-- 租户角色（TENANT）不可分配给平台用户，保证作用域隔离。
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

COMMENT ON TABLE role IS '角色表：记录平台与租户级别的角色定义，通过作用域隔离保证安全边界';
COMMENT ON COLUMN role.id IS '主键，自增序列';
COMMENT ON COLUMN role.code IS '角色编码：在同一作用域内唯一，用于程序识别';
COMMENT ON COLUMN role.name IS '角色名称，展示用';
COMMENT ON COLUMN role.description IS '角色描述，说明角色职责与权限范围';
COMMENT ON COLUMN role.scope_type IS '角色作用域：PLATFORM 平台角色 / TENANT 租户角色，不可跨域分配';
COMMENT ON COLUMN role.created_at IS '创建时间';
COMMENT ON COLUMN role.updated_at IS '最后更新时间';

-- 角色码在同作用域内唯一
CREATE UNIQUE INDEX uk_role_code_scope ON role (code, scope_type);

-- 权限表
-- code 为稳定字符串编码，在 @PreAuthorize 和 JwtAuthenticationFilter 中使用，
-- 格式为 PERM_{code}，例如 PERM_TENANT_READ。
CREATE TABLE permission
(
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE permission IS '权限表：记录系统中所有可分配的权限项，权限码为稳定字符串编码';
COMMENT ON COLUMN permission.id IS '主键，自增序列';
COMMENT ON COLUMN permission.code IS '权限编码：全局唯一，格式为 PERM_{code}，供 @PreAuthorize 使用';
COMMENT ON COLUMN permission.name IS '权限名称，展示用';
COMMENT ON COLUMN permission.description IS '权限描述，说明该权限允许执行的操作';
COMMENT ON COLUMN permission.created_at IS '创建时间';
COMMENT ON COLUMN permission.updated_at IS '最后更新时间';

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

COMMENT ON TABLE user_role IS '用户角色关联表：记录用户与角色的多对多映射关系';
COMMENT ON COLUMN user_role.id IS '主键，自增序列';
COMMENT ON COLUMN user_role.user_id IS '用户 ID，引用 user_account 表';
COMMENT ON COLUMN user_role.role_id IS '角色 ID，引用 role 表，用户通过角色间接获得权限';
COMMENT ON COLUMN user_role.created_at IS '分配时间';

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

COMMENT ON TABLE role_permission IS '角色权限关联表：记录角色与权限的多对多映射关系，角色通过此关联获得对应权限';
COMMENT ON COLUMN role_permission.id IS '主键，自增序列';
COMMENT ON COLUMN role_permission.role_id IS '角色 ID，引用 role 表';
COMMENT ON COLUMN role_permission.permission_id IS '权限 ID，引用 permission 表';
COMMENT ON COLUMN role_permission.created_at IS '分配时间';

-- 角色权限唯一约束：同一权限不可重复分配给同一角色
CREATE UNIQUE INDEX uk_role_permission ON role_permission (role_id, permission_id);

-- ============================================================
-- 静态种子数据：系统权限、角色及角色↔权限映射
-- 这些数据在应用启动时通过 Flyway 自动写入，无需手工维护。
-- V2 迁移会在此基础上追加细粒度权限。
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
