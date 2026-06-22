import { describe, expect, it } from 'vitest'

describe('card management module', () => {
  it('card service exposes the expected api surface', async () => {
    const svc = await import('@/services/card')
    for (const fn of [
      'redeemCard',
      'createBatch', 'listBatches', 'fetchBatchDetail', 'listCardsInBatch',
      'enableBatch', 'disableBatch', 'enableCard', 'disableCard',
      'listAllBatches',
    ]) {
      expect(typeof (svc as any)[fn]).toBe('function')
    }
  })

  it('auth store exposes card permission getters', async () => {
    const mod = await import('@/stores/auth')
    const { createPinia, setActivePinia } = await import('pinia')
    setActivePinia(createPinia())
    const store = mod.useAuthStore()
    for (const g of [
      'canRedeemCards', 'canManageCards', 'canManageCrossTenantCards', 'canReadCardRecords',
    ]) {
      expect(g in store).toBe(true)
    }
  })

  it('CardRedeemView source includes redemption tokens', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../views/CardRedeemView.vue'), 'utf8')
    expect(src).toContain('redeemCard')
    expect(src).toContain('fetchMyAccount')
    // 卡密充值结果展示
    expect(src).toContain('lastResult')
  })

  it('CardBatchManagementView source includes batch creation tokens', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../views/CardBatchManagementView.vue'), 'utf8')
    expect(src).toContain('createBatch')
    expect(src).toContain('listBatches')
    // 创建对话框必备：动态多面额组
    expect(src).toContain('createGroups')
    // 一次性卡密展示：revealState 控制弹窗
    expect(src).toContain('plaintexts')
    expect(src).toContain('revealState')
  })

  it('RechargeCardRevealPanel enforces one-time display safety', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../components/RechargeCardRevealPanel.vue'), 'utf8')
    // 必须不可关闭：closable=false 且 mask-closable=false，强制用户主动确认保存
    expect(src).toContain(':closable="false"')
    expect(src).toContain(':mask-closable="false"')
    // 必须提供 TXT / CSV 本地导出 + 复制全部
    expect(src).toContain('exportTxt')
    expect(src).toContain('exportCsv')
    expect(src).toContain('copyAll')
    // 文案：完整卡密仅展示一次警示
    expect(src).toContain('仅在本次生成后展示一次')
    // 关闭按钮文案
    expect(src).toContain('我已妥善保存全部卡密')
  })

  it('http error map covers new card error codes', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../services/http.ts'), 'utf8')
    for (const code of [
      'CARD_CODE_INVALID', 'CARD_NOT_FOUND', 'CARD_ALREADY_REDEEMED',
      'CARD_DISABLED', 'CARD_EXPIRED', 'CARD_BATCH_DISABLED',
      'CARD_CROSS_TENANT_REDEEM', 'CARD_BATCH_NOT_FOUND',
      'CARD_BATCH_COUNT_EXCEEDED',
    ]) {
      expect(src).toContain(code)
    }
    // 关键文案严格遵循 AGENT.md 规范
    expect(src).toContain('该卡密已被核销')
    expect(src).toContain('卡密格式不正确')
  })

  it('router declares card routes with guards', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../router/index.ts'), 'utf8')
    expect(src).toContain("'cards/redeem'")
    expect(src).toContain("'cards/manage'")
    expect(src).toContain('tenants/:tenantId/cards/manage')
    expect(src).toContain('canRedeemCards')
    expect(src).toContain('canManageCards')
  })

  it('ConsoleShell menu shows card entries when permission granted', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../components/ConsoleShell.vue'), 'utf8')
    expect(src).toContain('卡密充值')
    expect(src).toContain('卡密管理')
    expect(src).toContain('canRedeemCards')
    expect(src).toContain('canManageCards')
  })
})
