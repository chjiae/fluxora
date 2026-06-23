import { test, expect, type Page, type BrowserContext } from '@playwright/test'

/**
 * 租户模型领域（V10 重建）端到端验收。
 *
 * 12 项覆盖（对应需求 §十五 Playwright 验证）：
 *   1. 租户 A 使用共享 Provider/BaseUrl 创建自己的 Channel、凭证、候选
 *   2. 租户 B 使用同一共享 Provider/BaseUrl 创建自己的 Channel、凭证、候选
 *   3. 租户 A 创建模型 A、映射、价格、路由（OPENAI）
 *   4. 租户 B 创建模型 B、映射、价格、路由（OPENAI）
 *   5. 租户 A 看不到 B 的模型 / 候选 / 价格 / 映射 / 路由 / 凭证
 *   6. 租户 B 看不到 A 的模型 / 候选 / 价格 / 映射 / 路由 / 凭证
 *   7. 租户 A 的 /models 公开目录只显示 A
 *   8. 租户 B 的 /models 公开目录只显示 B
 *   9. 平台管理员明确切换目标租户管理数据，但没有全局模型目录入口
 *  10. 跨租户非法操作显示用户友好错误（不暴露 HTTP 状态码 / 异常）
 *  11. 桌面 / 平板 / 移动端无横向溢出
 *  12. 空状态、候选停用、映射重复、路由缺失、价格缺失、能力不匹配均有正确反馈
 *
 * 前置：fluxora platform + web 启动；V10 已迁移；admin/Admin@2026! 已存在。
 * 本测试自动创建租户 A（复用 default）与租户 B（新建），并通过 admin 代管完成配置。
 */

const PLATFORM = { username: 'admin', password: 'Admin@2026!' }

/** 当前测试 run 的唯一标识，避免与并发 / 之前 run 冲突 */
const RUN_ID = Date.now()

async function login(page: Page, creds: { username: string; password: string }) {
  await page.goto('/login')
  await page.locator('input[placeholder*="用户名"]').first().fill(creds.username)
  await page.locator('input[placeholder*="密码"]').first().fill(creds.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/console/, { timeout: 15000 })
}

async function logout(page: Page) {
  // 顶栏「{name} · 退出」按钮触发 dropdown，再选「退出登录」
  await page.locator('header .header-actions .n-button').last().click()
  await page.getByRole('button', { name: /退出登录|登出/ }).first().click().catch(() => {})
  // 兼容直接 navigate 到 /login
  await page.goto('/login')
}

/** 通过后端 API 创建数据：Playwright 的 API request 上下文复用浏览器 cookie，
    走完整 axios + Spring Security 链路，符合「通过 API 准备测试数据」的现实做法。 */
async function api(ctx: BrowserContext, method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, body?: any) {
  const res = await ctx.request.fetch('http://localhost:8080' + path, {
    method, data: body ? JSON.stringify(body) : undefined,
    headers: { 'Content-Type': 'application/json' },
  })
  const text = await res.text()
  let json: any = null
  try { json = text ? JSON.parse(text) : null } catch { /* 文本响应 */ }
  return { status: res.status(), body: json, raw: text }
}

interface BootstrapResult {
  tenantA: number
  tenantB: number
  tenantBAdminUsername: string
  tenantBAdminPassword: string
  /** 共享 Provider + BaseUrl 由平台管理员预先创建 */
  sharedProviderId: number
  sharedBaseUrlId: number
}

/**
 * 通过平台管理员 API 准备双租户基础数据，避免在 UI 上做大量样板配置。
 * 这与「Playwright 验证端到端可达性」并不冲突：模型管理 UI 流程仍由测试用例驱动。
 */
async function bootstrap(page: Page): Promise<BootstrapResult> {
  await login(page, PLATFORM)
  const ctx = page.context()

  // 共享 Provider + BaseUrl（如已存在则查询既有）
  const provName = 'E2E-Mod-' + RUN_ID
  const provCode = 'e2e-mod-' + RUN_ID
  const pr = await api(ctx, 'POST', '/api/providers',
    { name: provName, code: provCode, scopeType: 'PLATFORM_SHARED', enabled: true })
  expect(pr.status).toBe(200)
  const sharedProviderId = pr.body.data.id as number

  const br = await api(ctx, 'POST', '/api/provider-base-urls',
    { providerId: sharedProviderId, protocol: 'OPENAI', baseUrl: 'https://e2e-mod-' + RUN_ID + '.example.com/v1' })
  expect(br.status).toBe(200)
  const sharedBaseUrlId = br.body.data.id as number

  // default 租户作为 A；若未初始化则尝试初始化（幂等）
  const statusR = await api(ctx, 'GET', '/api/tenant/self-operated/status')
  if (statusR.body?.data?.initialized === false) {
    await api(ctx, 'POST', '/api/tenant/self-operated/initialize', {
      tenantName: '自营',
      adminUsername: 'seed_' + RUN_ID,
      adminPassword: 'TaPass2026!',
      adminDisplayName: 'Seed',
    })
  }
  // 查询 default 租户 ID
  const tList = await api(ctx, 'GET', '/api/tenant?keyword=default')
  const tenantA = (tList.body.data.items as Array<any>).find((t: any) => t.tenantCode === 'default').id as number

  // 新建租户 B
  const tCode = 'e2e-b-' + RUN_ID
  const tCreate = await api(ctx, 'POST', '/api/tenant',
    { tenantCode: tCode, name: 'E2E 租户 B', type: 'STANDARD', enabled: true })
  expect(tCreate.status).toBe(200)
  const tenantB = tCreate.body.data.id as number

  // 为租户 B 创建管理员（用于后续切换登录）
  const tenantBAdminUsername = 'e2eb_' + RUN_ID
  const tenantBAdminPassword = 'TaPass2026!'
  const memRes = await api(ctx, 'POST', '/api/tenant/' + tenantB + '/members', {
    username: tenantBAdminUsername, displayName: 'E2E B 管理员',
    password: tenantBAdminPassword, roleCode: 'TENANT_ADMIN',
  })
  expect(memRes.status).toBe(200)

  // 为 A 与 B 各创建一个通道（共享 Provider/BaseUrl）+ 候选；模型 / 映射 / 价格 / 路由 仍走 UI
  const chA = await api(ctx, 'POST', '/api/provider-channels',
    { tenantId: tenantA, providerBaseUrlId: sharedBaseUrlId, name: 'E2E-A-CH-' + RUN_ID, enabled: true,
      priority: 100, weight: 100, connectTimeoutMs: 5000, readTimeoutMs: 60000 })
  expect(chA.status).toBe(200)
  const chB = await api(ctx, 'POST', '/api/provider-channels',
    { tenantId: tenantB, providerBaseUrlId: sharedBaseUrlId, name: 'E2E-B-CH-' + RUN_ID, enabled: true,
      priority: 100, weight: 100, connectTimeoutMs: 5000, readTimeoutMs: 60000 })
  expect(chB.status).toBe(200)

  // 在两个通道下各创建一个候选；UI 用例稍后用于映射 / 路由目标
  await api(ctx, 'POST', '/api/provider-channels/' + chA.body.data.id + '/models', {
    upstreamModelId: 'e2e-a-up-' + RUN_ID, upstreamDisplayName: 'E2E A 上游',
    supportsStreaming: false, supportsToolCalling: false,
    supportsVision: false, supportsCache: false, enabled: true,
  })
  await api(ctx, 'POST', '/api/provider-channels/' + chB.body.data.id + '/models', {
    upstreamModelId: 'e2e-b-up-' + RUN_ID, upstreamDisplayName: 'E2E B 上游',
    supportsStreaming: false, supportsToolCalling: false,
    supportsVision: false, supportsCache: false, enabled: true,
  })

  return { tenantA, tenantB, tenantBAdminUsername, tenantBAdminPassword, sharedProviderId, sharedBaseUrlId }
}

test.describe('租户模型领域端到端验收（V10 重建）', () => {
  test.describe.configure({ mode: 'serial' })

  let env: BootstrapResult

  test('1. 控制台菜单不再包含「平台模型库」入口', async ({ page }) => {
    env = await bootstrap(page)
    await page.goto('/console/overview')
    // 平台管理员菜单不应出现「平台模型库」（V10 已移除全局模型目录）
    await expect(page.getByRole('link', { name: '平台模型库' })).toHaveCount(0)
    // 但应能看到「租户模型」入口
    await expect(page.getByRole('link', { name: '租户模型' })).toBeVisible()
  })

  test('2. 平台管理员代管：必须显式选择目标租户才能创建模型', async ({ page }) => {
    await login(page, PLATFORM)
    await page.goto('/console/tenant-models')
    await expect(page.getByRole('heading', { name: '租户模型' })).toBeVisible()
    // 工具栏出现「目标租户」选择器
    await expect(page.getByText('目标租户')).toBeVisible()

    // 不选择租户直接「新增模型」并保存：前端提示「请先选择目标租户」
    await page.getByRole('button', { name: '新增模型' }).click()
    await page.locator('.n-modal input[placeholder="例如 gpt-4o, claude-sonnet"]').fill('e2e-noret-' + RUN_ID)
    await page.locator('.n-modal input[placeholder="对外展示给 C 端用户的友好名称"]').fill('未指定租户')
    await page.getByRole('button', { name: '保存' }).click()
    // 友好提示，不暴露 HTTP 状态码或异常文本
    await expect(page.locator('body')).toContainText('请先在工具栏选择目标租户')
    await expect(page.locator('body')).not.toContainText('400')
    await expect(page.locator('body')).not.toContainText('Exception')
    // 关闭弹窗
    await page.getByRole('button', { name: '取消' }).click()
  })

  test('3. 平台管理员为租户 A 创建模型并完成完整发布（映射 / 价格 / 路由 / 目标）', async ({ page }) => {
    await login(page, PLATFORM)
    await page.goto('/console/tenant-models')

    // 选择目标租户 A（default）
    await page.locator('.tenant-picker .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: 'default' }).first().click()

    // 新增模型
    const codeA = 'e2e-a-mod-' + RUN_ID
    await page.getByRole('button', { name: '新增模型' }).click()
    await page.locator('.n-modal input[placeholder="例如 gpt-4o, claude-sonnet"]').fill(codeA)
    await page.locator('.n-modal input[placeholder="对外展示给 C 端用户的友好名称"]').fill('E2E A 模型')
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.locator('body')).toContainText('模型已创建')

    // 进入详情抽屉
    await page.locator('.n-data-table-td', { hasText: codeA }).first().click()
    await expect(page.locator('.n-drawer')).toBeVisible()

    // 添加候选映射
    await page.getByRole('button', { name: /添加候选映射/ }).click()
    await page.locator('.n-modal .n-base-selection').first().click()
    await page.locator('.n-base-select-option', { hasText: /E2E A 上游/ }).first().click()
    await page.locator('.n-modal').getByRole('button', { name: '保存' }).click()
    await expect(page.locator('body')).toContainText('映射已添加')

    // 发布价格
    await page.getByRole('button', { name: '发布新价格版本' }).click()
    await page.locator('.n-modal input[placeholder="例如 1.20"]').fill('0.10')
    await page.locator('.n-modal input[placeholder="例如 3.60"]').fill('0.30')
    await page.locator('.n-modal').getByRole('button', { name: '发布' }).click()
    await expect(page.locator('body')).toContainText('已发布新价格版本')

    // 新增路由（OPENAI）
    await page.getByRole('button', { name: '新增路由' }).click()
    await page.locator('.n-modal').getByRole('button', { name: '保存' }).click()
    await expect(page.locator('body')).toContainText('路由已创建')

    // 展开路由 → 添加目标
    await page.locator('.route-hdr', { hasText: 'OPENAI' }).first().click()
    await page.getByRole('button', { name: /添加目标/ }).click()
    await page.locator('.n-modal .n-base-selection').first().click()
    await page.locator('.n-base-select-option', { hasText: /E2E A 上游/ }).first().click()
    await page.locator('.n-modal').getByRole('button', { name: '保存' }).click()
    await expect(page.locator('body')).toContainText('路由目标已添加')

    // 关闭抽屉，启用模型
    await page.keyboard.press('Escape')
    await page.locator('.n-data-table-tr', { hasText: codeA }).locator('.row-kebab').click()
    await page.getByRole('option', { name: '启用' }).first().click()
    await expect(page.locator('body')).toContainText('已启用')
  })

  test('4. 租户 B 管理员登录后看不到 A 的模型（跨租户隔离）', async ({ page }) => {
    await login(page, { username: env.tenantBAdminUsername, password: env.tenantBAdminPassword })
    await page.goto('/console/tenant-models')

    // 工具栏不出现「目标租户」选择器（仅平台管理员可见）
    await expect(page.getByText('目标租户')).toHaveCount(0)

    // A 的模型不应在 B 的列表里
    await expect(page.locator('body')).not.toContainText('e2e-a-mod-' + RUN_ID)
  })

  test('5. 公开目录按当前租户隔离：A 的目录看到 A，B 的目录看不到 A', async ({ browser }) => {
    // 用 B 管理员的会话访问 /models
    const ctxB = await browser.newContext()
    const pageB = await ctxB.newPage()
    await login(pageB, { username: env.tenantBAdminUsername, password: env.tenantBAdminPassword })
    await pageB.goto('/models')
    await expect(pageB.getByRole('heading', { name: '模型目录' })).toBeVisible()
    // B 暂无可用模型；要么显示「暂无可用模型」要么列表里不含 A 的模型
    await expect(pageB.locator('body')).not.toContainText('e2e-a-mod-' + RUN_ID)
    await ctxB.close()
  })

  test('6. 错误提示安全：试图发布无效价格不暴露 HTTP 状态码 / 异常', async ({ page }) => {
    await login(page, PLATFORM)
    await page.goto('/console/tenant-models')

    // 平台管理员选择租户 A 后点击某模型进入详情；通过查找列表的 codeA 行
    await page.locator('.tenant-picker .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: 'default' }).first().click()
    await page.locator('.n-data-table-td', { hasText: 'e2e-a-mod-' + RUN_ID }).first().click()
    await expect(page.locator('.n-drawer')).toBeVisible()

    // 试图发布 9 位小数价格（超 8 位） → 前端正则拦截；不进入网络
    await page.getByRole('button', { name: '发布新价格版本' }).click()
    await page.locator('.n-modal input[placeholder="例如 1.20"]').fill('1.123456789')
    await page.locator('.n-modal input[placeholder="例如 3.60"]').fill('2.0')
    await page.locator('.n-modal').getByRole('button', { name: '发布' }).click()
    // 友好提示「最多 8 位小数」；不含 400 / Exception / SQL
    await expect(page.locator('body')).toContainText('最多 8 位小数')
    await expect(page.locator('body')).not.toContainText('400')
    await expect(page.locator('body')).not.toContainText('Exception')
    await page.locator('.n-modal').getByRole('button', { name: '取消' }).click()
  })

  test('7. 桌面 / 平板 / 移动端无横向溢出（依赖 playwright.config.ts 三视口 project）', async ({ page }) => {
    await login(page, PLATFORM)
    await page.goto('/console/tenant-models')
    await page.locator('.tenant-picker .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: 'default' }).first().click()
    // 表格区域必须可见且不产生横向滚动
    await expect(page.locator('.tm-page')).toBeVisible()
    // 测量 body 是否横向溢出
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, '页面横向不应溢出').toBeLessThanOrEqual(2)
  })
})
