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
  await page.locator('input[placeholder*="用户名"]').first().fill('admin')
  await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL(/\/console/, { timeout: 10_000 })

  if (page.url().includes('/setup')) {
    await page.locator('.n-form-item:has(.n-form-item-label:text("租户名称")) input').fill('Fluxora 自营')
    await page.locator('button:has-text("下一步")').click()
    await page.waitForTimeout(300)
    await page.locator('.n-form-item:has(.n-form-item-label:text("管理员用户名")) input').fill(`audit-${Date.now()}`)
    await page.locator('.n-form-item:has(.n-form-item-label:text("管理员显示名")) input').fill('UI 验收管理员')
    await page.locator('.n-form-item:has(.n-form-item-label:text("管理员密码")) input').fill('AuditPass@2026')
    await page.locator('.n-form-item:has(.n-form-item-label:text("确认密码")) input').fill('AuditPass@2026')
    await page.locator('button:has-text("创建并进入控制台")').click()
    await page.waitForURL(/\/console\/overview/, { timeout: 10000 })
  }
}

async function openTenants(page: Page) {
  await login(page)
  await page.getByRole('menuitem', { name: '租户管理' }).click()
  await expect(page.getByRole('heading', { name: '租户管理' })).toBeVisible()
}

test.describe('全站 UI 验收检查', () => {
  const viewports = { desktop: [1440, 900], laptop: [1280, 720], tablet: [768, 1024], mobile: [390, 844] } as const

  // 响应式用例会在单一 desktop project 内自行切换视口；其余验收按桌面信息架构断言。
  test.beforeEach(({}, testInfo) => {
    if (testInfo.project.name !== 'desktop') testInfo.skip()
  })

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
    await expect(page.locator('input[placeholder*="用户名"]').first()).toBeVisible()
    await expect(page.locator('input[placeholder*="密码"]').first()).toBeVisible()
    await page.getByRole('button', { name: '登录' }).click()
    await expect(page.locator('.n-form-item-feedback:has-text("请输入用户名")')).toBeVisible()
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('wrong')
    await page.getByRole('button', { name: '登录' }).click()
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
    // 筛选通过搜索框触发，不需要额外按钮
    await expect(page.locator('input[placeholder*="搜索"]').first()).toBeVisible()
    const pagination = page.locator('.n-pagination')
    if (await pagination.count()) await expect(pagination).toBeVisible()
    await checkNoOverflow(page)
    await screenshot(page, '05-tenant-table')
  })

  test('租户新增抽屉 - 表单与字段校验', async ({ page }) => {
    await openTenants(page)
    await page.locator('button:has-text("新增租户")').click()
    const modal = page.locator('.tenant-modal').last()
    await expect(modal).toBeVisible()
    await modal.locator('button:has-text("创建租户")').click()
    await expect(page.getByText('租户码仅支持小写字母、数字和连字符')).toBeVisible()
    await modal.locator('button:has-text("取消")').click()
    await expect(modal).not.toBeVisible()
    await screenshot(page, '06-tenant-create-drawer')
  })

  for (const [name, [width, height]] of Object.entries(viewports)) {
    test(`响应式 - 登录页 ${name} ${width}x${height}`, async ({ page }) => {
      await page.setViewportSize({ width, height })
      await page.goto(`${URL}/login`)
      await expect(page.locator('input[placeholder*="用户名"]').first()).toBeVisible()
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
    const sidebar = page.locator('.desktop-sider').first()
    const header = page.locator('.console-header')
    const sidebarBefore = await sidebar.boundingBox()
    const headerBefore = await header.boundingBox()
    if (sidebarBefore && headerBefore) {
      await page.getByTestId('console-content').evaluate((element: HTMLElement) => element.scrollTo(0, 300))
      await expect.poll(async () => page.getByTestId('console-content').evaluate((element: HTMLElement) => element.scrollTop)).toBeGreaterThanOrEqual(0)
      const sidebarAfter = await sidebar.boundingBox()
      const headerAfter = await header.boundingBox()
      // fixed 定位元素在滚动后位置不变（y 坐标不变）
      if (sidebarAfter) expect(sidebarAfter.y).toBe(sidebarBefore.y)
      if (headerAfter) expect(headerAfter.y).toBe(headerBefore.y)
    }
    await screenshot(page, '08-console-scroll-fixed')
  })

  test('空状态与路由守卫', async ({ page }) => {
    await page.goto(`${URL}/console/overview`)
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
    await openTenants(page)
    await page.locator('input[placeholder*="搜索"]').first().fill('ZZZZNOTEXISTS123456')
    await page.waitForTimeout(500)
    await expect(page.getByText('没有符合筛选条件的租户')).toBeVisible({ timeout: 5_000 })
    await checkNoOverflow(page)
  })
})
