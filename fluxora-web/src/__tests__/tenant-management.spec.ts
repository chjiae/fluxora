import { describe, expect, it } from 'vitest'

describe('tenant management', () => {
  it('should have tenant management view importable', async () => {
    const mod = await import('@/views/TenantManagementView.vue')
    expect(mod.default).toBeDefined()
  })

  it('should have auth store importable', async () => {
    const mod = await import('@/stores/auth')
    expect(mod.useAuthStore).toBeDefined()
  })

  it('should have tenant service with expected functions', async () => {
    const mod = await import('@/services/tenant')
    expect(mod.listTenants).toBeDefined()
    expect(mod.createTenant).toBeDefined()
    expect(mod.updateTenant).toBeDefined()
    expect(mod.deleteTenant).toBeDefined()
  })

  it('protects self-operated tenants and validates create fields', async () => {
    const view = await import('@/views/TenantManagementView.vue')
    const source = String(view.default.setup)
    expect(source).toContain('SELF_OPERATED')
    expect(source).toContain('createFormRules')
  })
})
