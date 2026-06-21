import { describe, expect, it } from 'vitest'

/**
 * 成员管理模块的轻量 vitest，参照 tenant-management.spec.ts 风格：
 *   - 模块可被加载（不依赖运行时 mock）；
 *   - 视图 setup 源码包含本轮新增的关键 token，覆盖：
 *     · 双入口（tenantId 可选 prop） · 角色 / 状态过滤 · 重置密码 · 删除确认。
 */
describe('member management module', () => {
  it('member service exposes the expected api surface', async () => {
    const svc = await import('@/services/member')
    expect(typeof svc.listMembersByTenant).toBe('function')
    expect(typeof svc.listMembersInCurrentTenant).toBe('function')
    expect(typeof svc.createMember).toBe('function')
    expect(typeof svc.updateMemberProfile).toBe('function')
    expect(typeof svc.updateMemberRole).toBe('function')
    expect(typeof svc.enableMember).toBe('function')
    expect(typeof svc.disableMember).toBe('function')
    expect(typeof svc.deleteMember).toBe('function')
    expect(typeof svc.resetMemberPassword).toBe('function')
    expect(typeof svc.fetchAssignableRoles).toBe('function')
  })

  it('auth store exposes member permission getters', async () => {
    const mod = await import('@/stores/auth')
    // 通过 useAuthStore() 实例化后断言 getters 存在；store 是 Pinia setup-store
    const { createPinia, setActivePinia } = await import('pinia')
    setActivePinia(createPinia())
    const store = mod.useAuthStore()
    expect('canReadMembers' in store).toBe(true)
    expect('canCreateMember' in store).toBe(true)
    expect('canResetMemberPassword' in store).toBe(true)
    expect('canDeleteMember' in store).toBe(true)
  })

  it('view setup includes member-specific tokens', async () => {
    const view = await import('@/views/MemberManagementView.vue')
    const setupSrc = String(view.default.setup)
    // 双入口 prop
    expect(setupSrc).toContain('tenantId')
    // 服务调用
    expect(setupSrc).toContain('listMembersByTenant')
    expect(setupSrc).toContain('listMembersInCurrentTenant')
    expect(setupSrc).toContain('resetMemberPassword')
    expect(setupSrc).toContain('updateMemberRole')
    // 角色与状态过滤
    expect(setupSrc).toContain('TENANT_ADMIN')
    expect(setupSrc).toContain('TENANT_MEMBER')
  })

  it('http error map covers new member error codes', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../services/http.ts'),
      'utf8',
    )
    for (const code of [
      'USERNAME_DUPLICATE',
      'LAST_TENANT_ADMIN_PROTECTED',
      'ROLE_NOT_ASSIGNABLE',
      'MEMBER_NOT_FOUND',
      'CROSS_TENANT_ACCESS_DENIED',
      'PASSWORD_WEAK',
    ]) {
      expect(src).toContain(code)
    }
    // 关键文案：最后管理员保护必须使用固定中文，禁止暴露错误码
    expect(src).toContain('该租户至少需要保留一名启用状态的租户管理员')
  })

  it('router declares both nested and self-tenant member routes', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../router/index.ts'),
      'utf8',
    )
    // 嵌套路由（平台管理员入口）
    expect(src).toContain('tenants/:tenantId/members')
    // 自身租户入口（租户管理员入口）
    expect(src).toContain("path: 'members'")
    // 守卫校验 MEMBER_READ
    expect(src).toContain('canReadMembers')
  })
})
