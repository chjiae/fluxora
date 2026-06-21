-- ============================================================
-- Fluxora V2：增强租户模型、细粒度权限
-- 
-- 本迁移完成以下增强：
-- 1. tenant 表添加 description 字段，支持租户描述信息
-- 2. tenant 类型默认值从 THIRD_PARTY 改为 STANDARD，符合需求规范
-- 3. 新增 8 个细粒度权限码，取代旧的粗粒度 PLATFORM_ADMIN 权限：
--    - PLATFORM_CONSOLE_ACCESS  — 平台控制台访问（所有登录用户）
--    - TENANT_READ              — 查看租户列表
--    - TENANT_CREATE            — 创建租户
--    - TENANT_UPDATE            — 编辑租户基础信息
--    - TENANT_ENABLE            — 启用租户
--    - TENANT_DISABLE           — 停用租户
--    - TENANT_DELETE            — 删除租户
--    - TENANT_EXPIRE_SET        — 设置租户过期时间
-- 4. 更新角色-权限关联：
--    - PLATFORM_ADMIN 获得全部租户管理权限
--    - TENANT_ADMIN 仅获得 PLATFORM_CONSOLE_ACCESS（不可管理平台租户）
--    - TENANT_MEMBER 仅获得 PLATFORM_CONSOLE_ACCESS
-- 
-- 所有 INSERT 使用 ON CONFLICT DO NOTHING 保证幂等，支持重复执行。
-- ============================================================

-- 租户表新增描述字段，支持记录租户的补充说明信息
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS description VARCHAR(512);

COMMENT ON COLUMN tenant.description IS '租户描述：补充说明信息，如业务用途、归属部门等，可选';

-- 修改租户类型默认值：旧值 THIRD_PARTY 改为 STANDARD
-- 仅影响后续新创建的租户，不改变已有数据的类型值
ALTER TABLE tenant ALTER COLUMN type SET DEFAULT 'STANDARD';

-- ============================================================
-- 细粒度权限种子数据
-- 权限码为稳定字符串，在 @PreAuthorize 注解中使用，格式：PERM_{code}
-- ============================================================
INSERT INTO permission (code, name, description) VALUES
    ('PLATFORM_CONSOLE_ACCESS', '平台控制台访问', '允许登录并访问平台控制台'),
    ('TENANT_READ', '查看租户列表', '允许查看租户列表与详情'),
    ('TENANT_CREATE', '创建租户', '允许创建新租户'),
    ('TENANT_UPDATE', '编辑租户', '允许编辑租户基础信息'),
    ('TENANT_ENABLE', '启用租户', '允许启用已停用的租户'),
    ('TENANT_DISABLE', '停用租户', '允许停用启用的租户'),
    ('TENANT_DELETE', '删除租户', '允许逻辑删除租户'),
    ('TENANT_EXPIRE_SET', '设置租户过期', '允许设置或清除租户过期时间')
ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 角色-权限关联更新
-- 
-- PLATFORM_ADMIN 角色：获得全部平台级租户管理权限。
-- 对应权限码加 PERM_ 前缀后在 @PreAuthorize 中使用，
-- 例如 @PreAuthorize("hasAuthority('PERM_TENANT_READ')")。
-- ============================================================
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'PLATFORM_ADMIN'
  AND p.code IN (
    'PLATFORM_CONSOLE_ACCESS', 'PLATFORM_ADMIN',
    'TENANT_READ', 'TENANT_CREATE', 'TENANT_UPDATE',
    'TENANT_ENABLE', 'TENANT_DISABLE', 'TENANT_DELETE', 'TENANT_EXPIRE_SET'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- TENANT_ADMIN 角色：仅可访问平台控制台，不拥有任何平台租户管理权限。
-- 租户管理员无法查看、创建、编辑、启用、停用、删除或设置任何租户过期时间。
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'TENANT_ADMIN' AND p.code = 'PLATFORM_CONSOLE_ACCESS'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- TENANT_MEMBER 角色：仅可访问平台控制台，无管理权限。
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'TENANT_MEMBER' AND p.code = 'PLATFORM_CONSOLE_ACCESS'
ON CONFLICT (role_id, permission_id) DO NOTHING;
