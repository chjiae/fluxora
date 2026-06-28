import { describe, expect, it } from 'vitest'

describe('credit management module', () => {
  it('credit service exposes the expected api surface', async () => {
    const svc = await import('@/services/credit')
    for (const fn of [
      'fetchMyAccount', 'listMyTransactions',
      'fetchUserAccount', 'listTenantTransactions', 'adjustCredit', 'fetchTenantCreditStats',
      'fetchAdminAccount', 'listAllTransactions', 'fetchAdminCreditStats',
      'fetchAdjustableUsers',
    ]) {
      expect(typeof (svc as any)[fn]).toBe('function')
    }
  })

  it('reconciliation service exposes the expected api surface', async () => {
    const svc = await import('@/services/reconciliation')
    for (const fn of ['listPendingReconciliations', 'confirmNoCharge', 'confirmSettle']) {
      expect(typeof (svc as any)[fn]).toBe('function')
    }
  })

  it('auth store exposes credit permission getters', async () => {
    const mod = await import('@/stores/auth')
    const { createPinia, setActivePinia } = await import('pinia')
    setActivePinia(createPinia())
    const store = mod.useAuthStore()
    for (const g of [
      'canReadOwnCredit', 'canReadTenantCredit',
      'canAdjustTenantCredit', 'canAdjustCrossTenantCredit',
    ]) {
      expect(g in store).toBe(true)
    }
  })

  it('MyCreditView setup includes credit-specific tokens', async () => {
    const view = await import('@/views/MyCreditView.vue')
    const src = String(view.default.setup)
    expect(src).toContain('fetchMyAccount')
    expect(src).toContain('listMyTransactions')
    expect(src).toContain('directionFilter')
  })

  it('CreditManagementView setup includes adjust dialog tokens', async () => {
    const view = await import('@/views/CreditManagementView.vue')
    const src = String(view.default.setup)
    expect(src).toContain('adjustCredit')
    expect(src).toContain('adjustForm')
    expect(src).toContain('adjustTarget')
    // 扣减时必须二次确认：在 submitAdjust 中根据 direction === 'DEBIT' 走 dialog.warning
    expect(src).toContain('submitAdjust')
    expect(src).toContain('确认扣减')
  })

  it('http error map covers new credit error codes', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../services/http.ts'), 'utf8')
    for (const code of [
      'CREDIT_INSUFFICIENT', 'CREDIT_ACCOUNT_NOT_FOUND',
      'CREDIT_AMOUNT_INVALID', 'CREDIT_REASON_REQUIRED',
    ]) {
      expect(src).toContain(code)
    }
    // 关键文案：余额不足必须使用固定中文
    expect(src).toContain('当前额度不足')
  })

  it('router declares api-keys and credit routes with guards', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../router/index.ts'), 'utf8')
    expect(src).toContain("'api-keys'")
    expect(src).toContain("'credit'")
    expect(src).toContain("'credit/manage'")
    expect(src).toContain("'billing/reconciliations'")
    expect(src).toContain('canManageOwnApiKeys')
    expect(src).toContain('canReadOwnCredit')
    expect(src).toContain('canAdjustCrossTenantCredit')
  })
})
