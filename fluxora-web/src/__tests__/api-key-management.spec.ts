import { describe, expect, it } from 'vitest'

/**
 * API Key 管理 vitest（与 member-management.spec.ts 同款轻量风格）：
 *   - service / store / view / http / router 各做一次"可加载 + 关键 token 存在"检查。
 */
describe('api key management module', () => {
  it('apiKey service exposes the expected api surface', async () => {
    const svc = await import('@/services/apiKey')
    for (const fn of [
      'listMyApiKeys', 'fetchMyApiKeyStats', 'createMyApiKey',
      'getApiKey', 'updateApiKey', 'enableApiKey', 'disableApiKey', 'deleteApiKey',
      'listTenantApiKeys', 'fetchTenantApiKeyStats', 'createTenantApiKey',
      'listAllApiKeys', 'fetchAllApiKeyStats',
    ]) {
      expect(typeof (svc as any)[fn]).toBe('function')
    }
  })

  it('auth store exposes API Key permission getters', async () => {
    const mod = await import('@/stores/auth')
    const { createPinia, setActivePinia } = await import('pinia')
    setActivePinia(createPinia())
    const store = mod.useAuthStore()
    for (const g of ['canManageOwnApiKeys', 'canManageTenantApiKeys', 'canManageCrossTenantApiKeys']) {
      expect(g in store).toBe(true)
    }
  })

  it('MyApiKeysView setup includes key-specific tokens', async () => {
    const view = await import('@/views/MyApiKeysView.vue')
    const src = String(view.default.setup)
    expect(src).toContain('createMyApiKey')
    expect(src).toContain('revealState')
    expect(src).toContain('plaintext')
  })

  it('ApiKeyRevealPanel exists and uses non-closable, non-mask-closable modal', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../components/ApiKeyRevealPanel.vue'), 'utf8')
    // 强制不可通过 mask 或关闭按钮关闭，避免用户误关丢失 plaintext
    expect(src).toContain(':closable="false"')
    expect(src).toContain(':mask-closable="false"')
    // 必须使用 navigator.clipboard 复制；保留 execCommand fallback
    expect(src).toContain('navigator.clipboard')
    expect(src).toContain('execCommand')
    // 警告中文文案必须存在
    expect(src).toContain('该 API Key 仅展示一次')
  })

  it('http error map covers new API Key error codes', async () => {
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const src = await fs.readFile(
      path.resolve(__dirname, '../services/http.ts'), 'utf8')
    for (const code of [
      'API_KEY_NAME_INVALID', 'API_KEY_NOT_FOUND',
      'API_KEY_DISABLED_STATE', 'API_KEY_EXPIRED', 'API_KEY_DELETED_STATE',
    ]) {
      expect(src).toContain(code)
    }
  })
})
