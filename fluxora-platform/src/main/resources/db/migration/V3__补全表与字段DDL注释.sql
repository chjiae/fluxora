-- ============================================================
-- Fluxora V3：补全 PostgreSQL DDL 注释（COMMENT ON TABLE / COLUMN）
--
-- 本迁移为六张核心表及其关键字段添加数据库元数据注释。
-- 所有 COMMENT ON 语句是幂等的，多次执行不会产生副作用。
-- 注释写入数据库元数据（pg_description），可通过 psql \d+ 或
-- pg_catalog.obj_description() 查询，便于 DBA 和开发者理解表结构。
-- ============================================================

-- -------- tenant 租户表 --------
COMMENT ON TABLE tenant IS '租户表：记录平台中所有租户（自营与标准），租户码全局唯一，支持启用、过期与逻辑删除';
COMMENT ON COLUMN tenant.id IS '主键，自增序列';
COMMENT ON COLUMN tenant.tenant_code IS '租户码，全局唯一，用于业务标识与路由，创建后不可修改';
COMMENT ON COLUMN tenant.name IS '租户名称，对外展示用';
COMMENT ON COLUMN tenant.description IS '租户描述：补充说明信息，如业务用途、归属部门等，可选';
COMMENT ON COLUMN tenant.type IS '租户类型：SELF_OPERATED 自营租户（仅初始化创建）或 STANDARD 标准租户（API 创建）';
COMMENT ON COLUMN tenant.enabled IS '启用状态：false 时租户下所有用户无法登录和访问';
COMMENT ON COLUMN tenant.expire_at IS '过期时间：超过后即使 enabled=true 也视为已过期，NULL 表示永不过期';
COMMENT ON COLUMN tenant.is_deleted IS '逻辑删除标识：true 表示已删除，不可恢复，查询时默认排除';
COMMENT ON COLUMN tenant.created_at IS '创建时间';
COMMENT ON COLUMN tenant.updated_at IS '最后更新时间';

-- -------- user_account 用户账号表 --------
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

-- -------- role 角色表 --------
COMMENT ON TABLE role IS '角色表：记录平台与租户级别的角色定义，通过作用域隔离保证安全边界';
COMMENT ON COLUMN role.id IS '主键，自增序列';
COMMENT ON COLUMN role.code IS '角色编码：在同一作用域内唯一，用于程序识别';
COMMENT ON COLUMN role.name IS '角色名称，展示用';
COMMENT ON COLUMN role.description IS '角色描述，说明角色职责与权限范围';
COMMENT ON COLUMN role.scope_type IS '角色作用域：PLATFORM 平台角色 / TENANT 租户角色，不可跨域分配';
COMMENT ON COLUMN role.created_at IS '创建时间';
COMMENT ON COLUMN role.updated_at IS '最后更新时间';

-- -------- permission 权限表 --------
COMMENT ON TABLE permission IS '权限表：记录系统中所有可分配的权限项，权限码为稳定字符串编码';
COMMENT ON COLUMN permission.id IS '主键，自增序列';
COMMENT ON COLUMN permission.code IS '权限编码：全局唯一，格式为 PERM_{code}，供 @PreAuthorize 使用';
COMMENT ON COLUMN permission.name IS '权限名称，展示用';
COMMENT ON COLUMN permission.description IS '权限描述，说明该权限允许执行的操作';
COMMENT ON COLUMN permission.created_at IS '创建时间';
COMMENT ON COLUMN permission.updated_at IS '最后更新时间';

-- -------- user_role 用户角色关联表 --------
COMMENT ON TABLE user_role IS '用户角色关联表：记录用户与角色的多对多映射关系';
COMMENT ON COLUMN user_role.id IS '主键，自增序列';
COMMENT ON COLUMN user_role.user_id IS '用户 ID，引用 user_account 表';
COMMENT ON COLUMN user_role.role_id IS '角色 ID，引用 role 表，用户通过角色间接获得权限';
COMMENT ON COLUMN user_role.created_at IS '分配时间';

-- -------- role_permission 角色权限关联表 --------
COMMENT ON TABLE role_permission IS '角色权限关联表：记录角色与权限的多对多映射关系，角色通过此关联获得对应权限';
COMMENT ON COLUMN role_permission.id IS '主键，自增序列';
COMMENT ON COLUMN role_permission.role_id IS '角色 ID，引用 role 表';
COMMENT ON COLUMN role_permission.permission_id IS '权限 ID，引用 permission 表';
COMMENT ON COLUMN role_permission.created_at IS '分配时间';
