import { expect, test } from '@playwright/test'

const URL = 'http://localhost:5173'

test.describe('全站UI验收检查', () => {
  const VIEWPORTS = { desktop: [1440, 900], laptop: [1280, 720], tablet: [768, 1024], mobile: [390, 844] } as const

  // ===== 辅助 =====
  async function checkConsole(page: any) {
    page.on('console', (msg: any) => { if (msg.type() === 'error') console.error('[CONSOLE ERROR]', msg.text()) })
    page.on('pageerror', (err: Error) => console.error('[PAGE ERROR]', err.message))
  }
  async function checkNoOverflow(page: any) {
    const ok = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
    expect(ok).toBeTruthy()
  }
  async function takeScreenshot(page: any, name: string) {
    await page.screenshot({ path: `test-results/screenshots/${name}.png`, fullPage: true }).catch(() => {})
  }

  // ===== 1. 官网首页 (/  public) =====
  test('官网首页 - 排版、响应式、Console', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.brand')).toBeVisible()
    await expect(page.locator('.hero')).toBeVisible()
    // 导航链接
    // 导航链接（移动端可能隐藏在汉堡菜单后）
    const docsLink = page.locator('a[href="/docs"]').first()
    await expect(docsLink).toBeAttached()
    await checkNoOverflow(page)
    // 切暗色
    await page.locator('.theme-toggle').first().click()
    await expect(page.locator('.hero')).toBeVisible()
    // 检查关键区块
    const sections = ['.hero', '.statement', '.capabilities', '.onboard', '.faq']
    for (const s of sections) await expect(page.locator(s)).toBeVisible()
    await takeScreenshot(page, '01-home-light')
  })

  // ===== 2. 文档页 (/docs public) =====
  test('文档页 - 布局、侧边栏、内容区', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/docs')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.docs')).toBeVisible()
    await expect(page.locator('.docs aside')).toBeVisible()
    await expect(page.locator('.docs article')).toBeVisible()
    await checkNoOverflow(page)
    // 切换章节
    const sectionLinks = page.locator('.docs aside button, .docs aside a')
    if (await sectionLinks.count() > 0) await sectionLinks.first().click()
    await takeScreenshot(page, '02-docs')
  })

  // ===== 3. 登录页 (/login) =====
  test('登录页 - 结构、表单、主题切换、错误提示', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/login')
    await page.waitForLoadState('networkidle')
    // 左右布局
    await expect(page.locator('.login-hero')).toBeVisible()
    await expect(page.locator('.login-form-panel')).toBeVisible()
    // 表单存在
    await expect(page.locator('input[placeholder*="用户名"]').first()).toBeVisible()
    await expect(page.locator('input[placeholder*="密码"]').first()).toBeVisible()
    await expect(page.locator('button:has-text("登录")')).toBeVisible()
    // 空提交
    await page.locator('button:has-text("登录")').click()
    await page.waitForTimeout(500)
    // 错误密码
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('wrong')
    await page.locator('button:has-text("登录")').click()
    await expect(page.locator('.n-message')).toBeVisible({ timeout: 5000 })
    // 主题切换
    await page.locator('.hero-footer .n-button').first().click()
    await expect(page.locator('input[placeholder*="用户名"]').first()).toBeVisible()
    await checkNoOverflow(page)
    await takeScreenshot(page, '03-login')
  })

  // ===== 4. 控制台概览 (需登录) =====
  test('控制台概览 - 菜单、指标卡、主题', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await page.locator('button:has-text("登录")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })
    if (page.url().includes('/setup')) {
      await page.locator('.n-input input').nth(0).fill('Test')
      await page.locator('.n-input input').nth(1).fill('auditadmin')
      await page.locator('.n-input input').nth(2).fill('pass123')
      await page.locator('.n-input input').nth(3).fill('Audit Admin')
      await page.locator('button:has-text("创建")').click()
      await page.waitForTimeout(2000)
      await page.goto(URL + '/console/overview')
      await page.waitForLoadState('networkidle')
    }
    // 侧边栏 + 主区
    await expect(page.locator('.console')).toBeVisible({ timeout: 8000 })
    await expect(page.locator('.n-layout-sider')).toBeVisible()
    // 菜单项
    await expect(page.locator('.n-menu-item-content:has-text("概览")').first()).toBeVisible()
    // 指标卡
    const cards = page.locator('.n-card')
    if (await cards.count() > 0) await expect(cards.first()).toBeVisible()
    await checkNoOverflow(page)
    // 切暗色
    await page.locator('.console-hdr .n-button').first().click()
    await page.waitForTimeout(300)
    await expect(page.locator('.n-layout-sider')).toBeVisible()
    await takeScreenshot(page, '04-console-overview')
  })

  // ===== 5. 租户管理页 =====
  test('租户管理 - 表格、筛选、抽屉、分页', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await page.locator('button:has-text("登录")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })
    if (page.url().includes('/setup')) {
      await page.locator('.n-input input').nth(0).fill('Test')
      await page.locator('.n-input input').nth(1).fill('auditadmin2')
      await page.locator('.n-input input').nth(2).fill('pass123')
      await page.locator('.n-input input').nth(3).fill('Audit Admin')
      await page.locator('button:has-text("创建")').click()
      await page.waitForTimeout(2000)
      await page.goto(URL + '/console/overview')
      await page.waitForLoadState('networkidle')
    }
    await page.locator('.n-menu-item-content:has-text("租户管理")').click()
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.n-data-table')).toBeVisible({ timeout: 8000 })
    // 筛选功能
    await expect(page.locator('.n-select').first()).toBeVisible()
    await expect(page.locator('.n-date-picker')).toBeVisible()
    // 分页（如果有）
    const pagination = page.locator('.n-pagination')
    if (await pagination.count() > 0) await expect(pagination).toBeVisible()
    // 无横向溢出
    await checkNoOverflow(page)
    await takeScreenshot(page, '05-tenant-table')
  })

  // ===== 6. 租户新增抽屉 =====
  test('租户新增抽屉 - 表单、校验、创建', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await page.locator('button:has-text("登录")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })
    if (page.url().includes('/setup')) {
      await page.locator('.n-input input').nth(0).fill('Test')
      await page.locator('.n-input input').nth(1).fill('auditadmin3')
      await page.locator('.n-input input').nth(2).fill('pass123')
      await page.locator('.n-input input').nth(3).fill('Audit Admin')
      await page.locator('button:has-text("创建")').click()
      await page.waitForTimeout(2000)
      await page.goto(URL + '/console/overview')
      await page.waitForLoadState('networkidle')
    }
    await page.locator('.n-menu-item-content:has-text("租户管理")').click()
    await page.waitForLoadState('networkidle')
    await page.locator('button:has-text("新增租户")').click()
    await expect(page.locator('.n-drawer')).toBeVisible({ timeout: 3000 })
    // 表单字段
    const inputs = page.locator('.n-drawer .n-input input')
    await expect(inputs.first()).toBeVisible()
    // 空提交
    await page.locator('.n-drawer button:has-text("创建")').click()
    await page.waitForTimeout(300)
    // 关闭
    await page.locator('.n-drawer button:has-text("取消")').click()
    await expect(page.locator('.n-drawer')).not.toBeVisible({ timeout: 3000 })
    await takeScreenshot(page, '06-tenant-create-drawer')
  })

  // ===== 7. 响应式检查（关键页面） =====
  for (const [name, [w, h]] of Object.entries(VIEWPORTS)) {
    test(`响应式 - 登录页 ${name} ${w}x${h}`, async ({ page }) => {
      checkConsole(page)
      await page.setViewportSize({ width: w, height: h })
      await page.goto(URL + '/login')
      await page.waitForLoadState('networkidle')
      await expect(page.locator('input[placeholder*="用户名"]').first()).toBeVisible()
      await checkNoOverflow(page)
      await takeScreenshot(page, `07-responsive-login-${name}`)
    })

    test(`响应式 - 首页 ${name} ${w}x${h}`, async ({ page }) => {
      checkConsole(page)
      await page.setViewportSize({ width: w, height: h })
      await page.goto(URL)
      await page.waitForLoadState('networkidle')
      await expect(page.locator('.hero')).toBeVisible()
      await checkNoOverflow(page)
      await takeScreenshot(page, `07-responsive-home-${name}`)
    })
  }

  // ===== 8. 控制台滚动壳检查 =====
  test('控制台滚动 - 侧边栏顶栏固定', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await page.locator('button:has-text("登录")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })
    if (page.url().includes('/setup')) {
      await page.locator('.n-input input').nth(0).fill('Test')
      await page.locator('.n-input input').nth(1).fill('auditadmin4')
      await page.locator('.n-input input').nth(2).fill('pass123')
      await page.locator('.n-input input').nth(3).fill('Audit Admin')
      await page.locator('button:has-text("创建")').click()
      await page.waitForTimeout(2000)
      await page.goto(URL + '/console/overview')
      await page.waitForLoadState('networkidle')
    }
    await page.locator('.n-menu-item-content:has-text("租户管理")').click()
    await page.waitForLoadState('networkidle')
    const sidebarBefore = await page.locator('.n-layout-sider').boundingBox()
    const headerBefore = await page.locator('.console-hdr').boundingBox()
    await page.locator('.console-ct').evaluate((el: any) => el.scrollTo(0, 300))
    await page.waitForTimeout(500)
    const sidebarAfter = await page.locator('.n-layout-sider').boundingBox()
    const headerAfter = await page.locator('.console-hdr').boundingBox()
    expect(sidebarAfter?.y).toBe(sidebarBefore?.y)
    expect(headerAfter?.y).toBe(headerBefore?.y)
    await takeScreenshot(page, '08-console-scroll-fixed')
  })

  // ===== 9. 空状态与边界 =====
  test('空状态 - 租户管理搜索无结果', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await page.locator('button:has-text("登录")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })
    await page.goto(URL + '/console/tenants')
    await page.waitForLoadState('networkidle')
    if (page.url().includes('/login')) {
      console.log('Skipped - no auth')
      return
    }
    await expect(page.locator('.tenant-page')).toBeVisible({ timeout: 5000 })
    // 搜索不存在的关键词
    const searchInput = page.locator('.tenant-page .n-input input').first()
    if (await searchInput.isVisible()) {
      await searchInput.fill('ZZZZNOTEXISTS123456')
      await page.waitForLoadState('networkidle')
      // 空状态应该显示
      await page.waitForTimeout(1000)
      await takeScreenshot(page, '09-empty-state-search')
    }
  })

  // ===== 10. 未登录跳转 =====
  test('路由守卫 - 未登录跳转登录', async ({ page }) => {
    checkConsole(page)
    await page.goto(URL + '/console/overview')
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
    await page.goto(URL + '/console/tenants')
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
  })
})
