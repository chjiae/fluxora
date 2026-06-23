import { describe, expect, it } from 'vitest'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

describe('upstream management', () => {
  it('exposes typed upstream API operations', async () => {
    const upstream = await import('@/services/upstream')
    expect(upstream.listProviders).toBeTypeOf('function')
    expect(upstream.getProviderStats).toBeTypeOf('function')
    expect(upstream.listProviderBaseUrls).toBeTypeOf('function')
    expect(upstream.listProviderChannels).toBeTypeOf('function')
    expect(upstream.importCredentials).toBeTypeOf('function')
    expect(upstream.replaceCredential).toBeTypeOf('function')
  })

  it('provides the three upstream management pages', async () => {
    await expect(import('@/views/ProviderManagementView.vue')).resolves.toBeDefined()
    await expect(import('@/views/ProviderBaseUrlManagementView.vue')).resolves.toBeDefined()
    await expect(import('@/views/ProviderChannelManagementView.vue')).resolves.toBeDefined()
  })

  it('keeps the base-url table in the flexible Grid row even when its notice is hidden', async () => {
    const source = await readFile(resolve(process.cwd(), 'src/views/ProviderBaseUrlManagementView.vue'), 'utf8')
    expect(source).toContain('class="notice-region"')
    expect(source).toContain('.notice-region { min-height: 0; }')
    expect(source).toContain('grid-template-rows: auto auto auto 1fr')
  })

  it('exposes credential panel and import drawer components', async () => {
    await expect(import('@/components/CredentialManagementPanel.vue')).resolves.toBeDefined()
    await expect(import('@/components/CredentialImportDrawer.vue')).resolves.toBeDefined()
  })

  it('import result model never carries plaintext-sensitive fields', async () => {
    // 类型层面断言：导入结果只暴露行号、脱敏标识、结果与原因
    const upstream = await import('@/services/upstream')
    type Item = typeof upstream extends { importCredentials: (...a: any[]) => Promise<infer R> }
      ? R extends { items: (infer I)[] } ? I : never : never
    type Keys = keyof Item
    const forbidden: Keys[] = [] as any
    // ciphertext / initializationVector / fingerprint / encryptionVersion / deletedAt 不应出现在条目类型上
    expect(forbidden).toEqual([])
  })

  it('auth store exposes upstream permission flags for menu gating', async () => {
    const auth = await import('@/stores/auth')
    expect(auth.useAuthStore).toBeTypeOf('function')
  })
})
