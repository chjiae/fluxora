import { describe, expect, it } from 'vitest'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

/**
 * 租户模型领域（V10 重建）前端冒烟测试。
 *
 * 覆盖：
 * - services/tenantModel.ts 暴露完整 API（模型 CRUD / 候选 / 映射 / 价格 / 路由 / 路由目标 / 公开目录）；
 * - 视图与组件文件存在且可被 Vite 解析；
 * - 列表页保留五行 Grid 节奏与 MetricStrip 复用；
 * - 公开目录视图不引用任何上游 / 候选 / 凭证内部接口；
 * - 候选面板已剥离全局模型映射按钮（V10 后不再存在）。
 */
describe('tenant model management', () => {
  it('exposes typed tenant-model API operations', async () => {
    const m = await import('@/services/tenantModel')
    // 模型本体
    expect(m.listTenantModels).toBeTypeOf('function')
    expect(m.getTenantModelStats).toBeTypeOf('function')
    expect(m.getTenantModel).toBeTypeOf('function')
    expect(m.createTenantModel).toBeTypeOf('function')
    expect(m.updateTenantModel).toBeTypeOf('function')
    expect(m.enableTenantModel).toBeTypeOf('function')
    expect(m.disableTenantModel).toBeTypeOf('function')
    expect(m.deleteTenantModel).toBeTypeOf('function')
    // 候选 CRUD + 同步
    expect(m.listChannelCandidates).toBeTypeOf('function')
    expect(m.createChannelCandidate).toBeTypeOf('function')
    expect(m.updateChannelCandidate).toBeTypeOf('function')
    expect(m.enableChannelCandidate).toBeTypeOf('function')
    expect(m.disableChannelCandidate).toBeTypeOf('function')
    expect(m.deleteChannelCandidate).toBeTypeOf('function')
    expect(m.syncChannelCandidates).toBeTypeOf('function')
    // 候选映射
    expect(m.listMappings).toBeTypeOf('function')
    expect(m.createMapping).toBeTypeOf('function')
    expect(m.updateMapping).toBeTypeOf('function')
    expect(m.deleteMapping).toBeTypeOf('function')
    // 价格
    expect(m.getCurrentPrice).toBeTypeOf('function')
    expect(m.getPriceHistory).toBeTypeOf('function')
    expect(m.publishPrice).toBeTypeOf('function')
    // 路由 + 目标
    expect(m.listRoutes).toBeTypeOf('function')
    expect(m.createRoute).toBeTypeOf('function')
    expect(m.updateRoute).toBeTypeOf('function')
    expect(m.deleteRoute).toBeTypeOf('function')
    expect(m.listRouteTargets).toBeTypeOf('function')
    expect(m.createRouteTarget).toBeTypeOf('function')
    expect(m.updateRouteTarget).toBeTypeOf('function')
    expect(m.deleteRouteTarget).toBeTypeOf('function')
    // C 端目录
    expect(m.listPublicModels).toBeTypeOf('function')
  })

  it('provides the tenant-model view, public catalog and candidates panel', async () => {
    await expect(import('@/views/TenantModelManagementView.vue')).resolves.toBeDefined()
    await expect(import('@/views/PublicModelCatalogView.vue')).resolves.toBeDefined()
    await expect(import('@/components/ChannelModelCandidatesPanel.vue')).resolves.toBeDefined()
  })

  it('keeps the five-row Grid rhythm and MetricStrip reuse in TenantModelManagementView', async () => {
    const source = await readFile(resolve(process.cwd(), 'src/views/TenantModelManagementView.vue'), 'utf8')
    expect(source).toContain('grid-template-rows: auto auto auto 1fr auto')
    expect(source).toContain("import MetricStrip from '@/components/MetricStrip.vue'")
    expect(source).toContain('getTenantModelStats')
  })

  it('public catalog view never references provider/credential/route internals', async () => {
    const source = await readFile(resolve(process.cwd(), 'src/views/PublicModelCatalogView.vue'), 'utf8')
    // C 端目录不得直接调用任何上游或路由模型接口
    expect(source).not.toContain('upstream')
    expect(source).not.toContain('provider')
    expect(source).not.toContain('credential')
    expect(source).not.toContain('candidate')
    expect(source).not.toContain('listRoutes')
    expect(source).not.toContain('listRouteTargets')
    expect(source).not.toContain('inboundProtocol')
    // 只通过 listPublicModels 拿数据
    expect(source).toContain("import { listPublicModels")
  })

  it('candidate panel no longer offers platform-model mapping (V10 removed global models)', async () => {
    const source = await readFile(resolve(process.cwd(), 'src/components/ChannelModelCandidatesPanel.vue'), 'utf8')
    expect(source).not.toContain('platformModel')
    expect(source).not.toContain('PLATFORM_MODEL')
    expect(source).not.toContain('platform-models')
    // 应该使用新的候选服务函数（含同步）
    expect(source).toContain('createChannelCandidate')
    expect(source).toContain('deleteChannelCandidate')
    expect(source).toContain('syncChannelCandidates')
    expect(source).toContain('async function runSync')
  })

  it('auth store exposes V10 TENANT_MODEL_* permission flags', async () => {
    const source = await readFile(resolve(process.cwd(), 'src/stores/auth.ts'), 'utf8')
    expect(source).toContain("'TENANT_MODEL_READ'")
    expect(source).toContain("'TENANT_MODEL_MANAGE'")
    expect(source).toContain("'TENANT_MODEL_CROSS_TENANT_MANAGE'")
    expect(source).toContain("'TENANT_MODEL_PUBLIC_READ'")
    // 旧的 MODEL_CATALOG_* / MODEL_PLATFORM_* 必须已被移除
    expect(source).not.toContain('MODEL_CATALOG_READ')
    expect(source).not.toContain('MODEL_PLATFORM_MANAGE')
  })
})
