-- ============================================================
-- Fluxora V4：成员管理与软删除字段统一
--
-- 本迁移完成两件事，按顺序在单一事务中执行：
--
-- 1. 统一软删除规范：
--    - 历史 tenant.is_deleted BOOLEAN 字段改造为 tenant.deleted_at TIMESTAMPTZ NULL；
--    - user_account 新增 deleted_at TIMESTAMPTZ NULL；
--    - 两张表的「业务唯一字段」改为部分唯一索引（WHERE deleted_at IS NULL），
--      使被软删除记录释放命名空间，便于后续重新创建同名条目。
--    NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，便于审计、未来窗口期恢复
--    与故障排查。该规则在 AGENT.md「软删除字段规范」章节有详细约束。
--
-- 2. 引入成员管理所需的 7 个细粒度权限码，同时授予 PLATFORM_ADMIN 与
--    TENANT_ADMIN 两个角色（跨租户、最后管理员保护、角色升级保护在服务层
--    强制执行，权限码本身仅做粗粒度网关）：
--      MEMBER_READ            — 查看租户成员
--      MEMBER_CREATE          — 创建租户成员
--      MEMBER_UPDATE          — 编辑成员基础资料与角色
--      MEMBER_ENABLE          — 启用成员
--      MEMBER_DISABLE         — 停用成员
--      MEMBER_DELETE          — 软删除成员
--      MEMBER_PASSWORD_RESET  — 重置成员密码
--
-- 所有 INSERT / DDL 均设计为幂等（IF EXISTS / IF NOT EXISTS / ON CONFLICT），
-- 重复执行不会破坏现有数据。
-- ============================================================

-- ------------------------------------------------------------
-- 一、tenant 表：is_deleted BOOLEAN → deleted_at TIMESTAMPTZ
-- ------------------------------------------------------------

-- 1.1 新增 deleted_at 列（如果已存在则跳过，保证幂等）
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN tenant.deleted_at IS '逻辑删除时间戳：NULL 表示未删除；非 NULL 表示已删除并记录删除时刻，用于审计与未来窗口期恢复';

-- 1.2 回填历史数据：原 is_deleted = TRUE 的行使用 updated_at 作为 deleted_at 近似值
--     未删除的行保持 deleted_at = NULL
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'tenant' AND column_name = 'is_deleted'
    ) THEN
        UPDATE tenant SET deleted_at = updated_at WHERE is_deleted = TRUE AND deleted_at IS NULL;
    END IF;
END
$$;

-- 1.3 重建租户码唯一索引：旧索引依赖 is_deleted 列，必须先删除再以 deleted_at 重建
DROP INDEX IF EXISTS uk_tenant_tenant_code;
CREATE UNIQUE INDEX uk_tenant_tenant_code ON tenant (tenant_code) WHERE deleted_at IS NULL;

-- 1.4 删除旧的 is_deleted 列（CASCADE 不需要，因为唯一索引已先于此删除）
ALTER TABLE tenant DROP COLUMN IF EXISTS is_deleted;

-- ------------------------------------------------------------
-- 二、user_account 表：新增 deleted_at + 用户名部分唯一索引
-- ------------------------------------------------------------

-- 2.1 新增 deleted_at 列
ALTER TABLE user_account ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN user_account.deleted_at IS '逻辑删除时间戳：NULL 表示未删除；非 NULL 表示已被管理员软删除，账号不可登录、不可被任何受保护接口认定为有效用户';

-- 2.2 用户名唯一索引由全局唯一改为部分唯一：软删除后用户名可被重新注册
--     这与 tenant_code 的部分唯一索引语义保持一致
DROP INDEX IF EXISTS uk_user_account_username;
CREATE UNIQUE INDEX uk_user_account_username ON user_account (username) WHERE deleted_at IS NULL;

-- 2.3 新增 tenant_id 索引：成员管理页按租户分页查询是最高频路径，
--     与 deleted_at IS NULL 过滤组合命中
CREATE INDEX IF NOT EXISTS idx_user_account_tenant_id ON user_account (tenant_id) WHERE deleted_at IS NULL;

-- ------------------------------------------------------------
-- 三、新增 7 个成员管理权限码
--     与 V2 的 TENANT_* 权限同样采用稳定字符串编码，
--     在 @PreAuthorize 中使用形如 hasAuthority('PERM_MEMBER_READ') 调用。
-- ------------------------------------------------------------
INSERT INTO permission (code, name, description) VALUES
    ('MEMBER_READ',           '查看租户成员', '允许查看租户成员列表与详情'),
    ('MEMBER_CREATE',         '创建租户成员', '允许在租户内创建新成员'),
    ('MEMBER_UPDATE',         '编辑租户成员', '允许编辑成员基础资料与调整成员角色'),
    ('MEMBER_ENABLE',         '启用成员',     '允许启用已停用的租户成员'),
    ('MEMBER_DISABLE',        '停用成员',     '允许停用启用中的租户成员'),
    ('MEMBER_DELETE',         '删除成员',     '允许软删除租户成员'),
    ('MEMBER_PASSWORD_RESET', '重置成员密码', '允许由管理员重置租户成员登录密码')
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------
-- 四、角色-权限绑定
--
-- PLATFORM_ADMIN 与 TENANT_ADMIN 同时获得全部 MEMBER_* 权限。
-- 真正的差异在服务层强制：
--   - 平台管理员可操作任意租户的成员；
--   - 租户管理员被强制使用自身 tenantId、不可分配 TENANT_ADMIN/PLATFORM_ADMIN 角色；
--   - 最后一名启用的 TENANT_ADMIN 受保护，不可被停用、删除或降级。
--
-- 权限码层面把两个角色对齐，避免前端因权限码差异而出现不同入口，
-- 业务边界统一收敛到 MemberService。
-- ------------------------------------------------------------
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code IN ('PLATFORM_ADMIN', 'TENANT_ADMIN')
  AND p.code IN (
    'MEMBER_READ', 'MEMBER_CREATE', 'MEMBER_UPDATE',
    'MEMBER_ENABLE', 'MEMBER_DISABLE', 'MEMBER_DELETE',
    'MEMBER_PASSWORD_RESET'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;
