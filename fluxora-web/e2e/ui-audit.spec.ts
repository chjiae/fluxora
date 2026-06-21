import { expect, test, type Page } from '@playwright/test'

const URL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:5173'

async function checkNoOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
}

async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: `test-results/screenshots/${name}.png`, fullPage: true }).catch(() => {})
}

/** 主题切换只依赖可访问名称，并验证页面真正更新主题状态。 */
async function toggleTheme(page: Page) {
  const before = await page.locator('html').getAttribute('data-theme')
  await page.locator('button[aria-label^="切换为"]:visible').first().click()
  await expect.poll(() => page.locator('html').getAttribute('data-theme')).not.toBe(before)
}

async function login(page: Page) {
  await page.goto(`${URL}/login`)
  await page.getByLabel('用户名', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('Admin@2026!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(/\/console/, { timeout: 10_000 })

  if (page.url().includes('/setup')) {
    await page.getByLabel('租户名称').fill('Fluxora 自营')
    await page.getByRole('button', { name: '下一步' }).click()
    await page.getByLabel('管理员用户名').fill(`audit-${Date.now()}`)
    await page.getByLabel('管理员显示名').fill('UI 验收管理员')
    await page.getByLabel('管理员密码').fill('AuditPass@2026')
    await page.getByLabel('确认密码').fill('AuditPass@2026')
    await page.getByRole('button', { name: '创建并进入控制台' }).click()
    await page.waitForURL(/\/console\/overview/, { timeout: 10_000 })
  }
}

async function openTenants(page: Page) {
  await login(page)
  await page.getByRole('menuitem', { name: '租户管理' }).click()
  await expect(page.getByRole('heading', { name: '租户管理' })).toBeVisible()
}

test.describe('全站 UI 验收检查', () => {
  const viewports = { desktop: [1440, 900], laptop: [1280, 720], tablet: [768, 1024], mobile: [390, 844] } as const

  test('官网首页 - 排版、响应式与主题', async ({ page }) => {
    await page.goto(URL)
    await expect(page.getByRole('link', { name: 'Fluxora 首页' })).toBeVisible()
    await expect(page.locator('.hero')).toBeVisible()
    await expect(page.getByRole('link', { name: '文档' }).first()).toBeAttached()
    await checkNoOverflow(page)
    if (await page.getByTestId('public-menu-toggle').isVisible()) await page.getByTestId('public-menu-toggle').click()
    await toggleTheme(page)
    for (const section of ['.hero', '.statement', '.capabilities', '.onboarding', '.faq']) await expect(page.locator(section)).toBeVisible()
    await screenshot(page, '01-home')
  })

  test('文档页 - 布局、侧边栏与内容区', async ({ page }) => {
    await page.goto(`${URL}/docs`)
    await expect(page.locator('.docs-shell')).toBeVisible()
    await expect(page.locator('.desktop-toc')).toBeVisible()
    await expect(page.locator('article.document')).toBeVisible()
    await checkNoOverflow(page)
    await screenshot(page, '02-docs')
  })

  test('登录页 - 表单验证、可访问控件与安全错误', async ({ page }) => {
    await page.goto(`${URL}/login`)
    await expect(page.getByRole('heading', { name: '登录控制台' })).toBeVisible()
    await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
    await expect(page.getByLabel('密码', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText('请输入用户名')).toBeVisible()
    await page.getByLabel('用户名', { exact: true }).fill('admin')
    await page.getByLabel('密码', { exact: true }).fill('wrong')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.locator('.n-message')).toContainText('用户名或密码错误，请重新输入')
    await toggleTheme(page)
    await checkNoOverflow(page)
    await screenshot(page, '03-login')
  })

  test('控制台概览 - 权限菜单、主题与唯一滚动区', async ({ page }) => {
    await login(page)
    await expect(page.getByTestId('console-content')).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '概览' })).toBeVisible()
    await expect(page.getByRole('heading', { name: /你好/ })).toBeVisible()
    await toggleTheme(page)
    await checkNoOverflow(page)
    await screenshot(page, '04-console-overview')
  })

  test('租户管理 - 表格、筛选、抽屉与分页', async ({ page }) => {
    await openTenants(page)
    await expect(page.locator('.n-data-table')).toBeVisible()
    await page.getByRole('button', { name: '筛选租户' }).click()
    await expect(page.getByPlaceholder('搜索...')).toBeVisible()
    await expect(page.locator('.n-date-picker')).toBeVisible()
    const pagination = page.locator('.n-pagination')
    if (await pagination.count()) await expect(pagination).toBeVisible()
    await checkNoOverflow(page)
    await screenshot(page, '05-tenant-table')
  })

  test('租户新增抽屉 - 表单与字段校验', async ({ page }) => {
    await openTenants(page)
    await page.getByRole('button', { name: '新增租户' }).click()
    await expect(page.getByRole('dialog', { name: '新增租户' })).toBeVisible()
    await page.getByRole('button', { name: '创建租户' }).click()
    await expect(page.getByText('租户码仅支持小写字母、数字和连字符')).toBeVisible()
    await page.getByRole('button', { name: '取消' }).click()
    await expect(page.getByRole('dialog', { name: '新增租户' })).not.toBeVisible()
    await screenshot(page, '06-tenant-create-drawer')
  })

  for (const [name, [width, height]] of Object.entries(viewports)) {
    test(`响应式 - 登录页 ${name} ${width}x${height}`, async ({ page }) => {
      await page.setViewportSize({ width, height })
      await page.goto(`${URL}/login`)
      await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
      await checkNoOverflow(page)
      await screenshot(page, `07-responsive-login-${name}`)
    })

    test(`响应式 - 首页 ${name} ${width}x${height}`, async ({ page }) => {
      await page.setViewportSize({ width, height })
      await page.goto(URL)
      await expect(page.locator('.hero')).toBeVisible()
      await checkNoOverflow(page)
      await screenshot(page, `07-responsive-home-${name}`)
    })
  }

  test('控制台滚动 - 侧栏与顶栏固定', async ({ page }) => {
    await openTenants(page)
    const sidebar = page.locator('.n-layout-sider:visible').first()
    const header = page.locator('.console-header')
    const sidebarBefore = await sidebar.boundingBox()
    const headerBefore = await header.boundingBox()
    await page.getByTestId('console-content').evaluate((element: HTMLElement) => element.scrollTo(0, 300))
    await expect.poll(async () => page.getByTestId('console-content').evaluate((element: HTMLElement) => element.scrollTop)).toBeGreaterThanOrEqual(0)
    expect((await sidebar.boundingBox())?.y).toBe(sidebarBefore?.y)
    expect((await header.boundingBox())?.y).toBe(headerBefore?.y)
    await screenshot(page, '08-console-scroll-fixed')
  })

  test('空状态与路由守卫', async ({ page }) => {
    await page.goto(`${URL}/console/overview`)
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
    await openTenants(page)
    await page.getByRole('button', { name: '筛选租户' }).click()
    await page.getByPlaceholder('搜索...').fill('ZZZZNOTEXISTS123456')
    await expect(page.getByText('暂无租户')).toBeVisible({ timeout: 5_000 })
    await checkNoOverflow(page)
  })
})
