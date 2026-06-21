import { expect, test } from '@playwright/test'

const WEB_URL = 'http://localhost:5173'

test.describe('真实验收（桌面端）', () => {
  test('平台管理员登录 → 自营初始化 → 租户CRUD → 自营管理员无权限', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'desktop') testInfo.skip()
    // ===== 1. 登录页 =====
    await page.goto(WEB_URL + '/login')
    await expect(page.locator('.login-form')).toBeVisible()

    // ===== 2. 错误密码不暴露技术信息 =====
    await page.fill('#username', 'admin')
    await page.fill('#password', 'wrong-password')
    await page.click('button:has-text("登录")')
    await expect(page.locator('.error-msg')).toBeVisible({ timeout: 5000 })
    const errText = await page.locator('.error-msg').textContent()
    expect(errText).not.toContain('401')
    expect(errText).not.toContain('Exception')
    expect(errText).not.toContain('SQL')

    // ===== 3. 正确登录 =====
    await page.fill('#username', 'admin')
    await page.fill('#password', 'Admin@2026!')
    await page.click('button:has-text("登录")')
    await page.waitForURL(/\/console/, { timeout: 10000 })

    // ===== 4. 如首次，完成自营初始化 =====
    if (page.url().includes('/console/setup')) {
      await expect(page.locator('.setup-form')).toBeVisible()
      await page.fill('#tenantName', 'Fluxora 自营')
      await page.fill('#adminUser', 'e2eadmin')
      await page.fill('#adminPass', 'e2epass123')
      await page.fill('#adminDisplay', 'E2E 管理员')
      await page.click('button:has-text("创建自营租户")')
      await page.waitForURL(/\/console/, { timeout: 10000 })
      const btn = page.locator('button:has-text("进入控制台")')
      if (await btn.isVisible({ timeout: 3000 }).catch(() => false)) await btn.click()
    }

    // ===== 5. 确认在控制台 =====
    await expect(page.locator('.console')).toBeVisible({ timeout: 8000 })

    // ===== 6. 进入租户管理 =====
    await page.click('a[href="/console/tenants"]')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table')).toBeVisible({ timeout: 5000 })

    // ===== 7. 创建 STANDARD 租户 =====
    const tenantCode = 'e2e-' + Date.now()
    await page.click('button:has-text("新增租户")')
    await expect(page.locator('.drawer')).toBeVisible({ timeout: 3000 })
    await page.locator('.drawer input').nth(0).fill(tenantCode)
    await page.locator('.drawer input').nth(1).fill('E2E 测试租户')
    await page.locator('.drawer button:has-text("创建租户")').click({ force: true })
    await expect(page.locator('.drawer')).not.toBeVisible({ timeout: 8000 })

    // ===== 8. 搜索验证 =====
    await page.fill('.search-box input', tenantCode)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table tbody tr')).toHaveCount(1, { timeout: 5000 })

    // ===== 9. 详情抽屉 → 编辑 =====
    await page.locator('.tenant-table tbody tr').first().click()
    await expect(page.locator('.drawer')).toBeVisible({ timeout: 3000 })
    await page.locator('.drawer button:has-text("编辑资料")').click()
    await page.locator('.drawer input').nth(1).fill('E2E 已编辑')
    await page.locator('.drawer button:has-text("保存")').click()
    await page.waitForLoadState('networkidle')

    // ===== 10. 停用 =====
    await page.locator('.tenant-table tbody tr').first().click()
    await expect(page.locator('.drawer')).toBeVisible({ timeout: 3000 })
    await page.locator('.drawer button:has-text("停用租户")').click()
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    await page.locator('.dialog button:has-text("确认停用")').click()
    await page.waitForLoadState('networkidle')

    // ===== 11. 启用 =====
    await page.locator('.tenant-table tbody tr').first().click()
    await expect(page.locator('.drawer')).toBeVisible({ timeout: 3000 })
    await page.locator('.drawer button:has-text("启用租户")').click()
    await page.waitForLoadState('networkidle')

    // ===== 12. 设置过期 =====
    await page.locator('.btn-text:has-text("设置过期时间")').first().click()
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    await page.locator('.dialog input[type="date"]').fill('2099-12-31')
    await page.locator('.dialog button:has-text("保存")').click()
    await expect(page.locator('.dialog')).not.toBeVisible({ timeout: 5000 })

    // ===== 13. 删除 =====
    await page.locator('.tenant-table tbody tr').first().click()
    await expect(page.locator('.drawer')).toBeVisible({ timeout: 3000 })
    await page.locator('.drawer button:has-text("删除租户")').click()
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    await page.locator('.dialog button:has-text("确认删除")').click()
    await page.waitForLoadState('networkidle')

    // ===== 14. 确认已删除 =====
    await page.fill('.search-box input', tenantCode)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.empty-state')).toBeVisible({ timeout: 5000 })

    // ===== 15. 退出 → 自营管理员登录 → 无租户管理 =====
    await page.locator('.logout-btn').click({ force: true })
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.login-form')).toBeVisible({ timeout: 5000 })
    await page.fill('#username', 'e2eadmin')
    await page.fill('#password', 'e2epass123')
    await page.click('button:has-text("登录")')
    await page.waitForURL(/\/console/, { timeout: 10000 })
    await expect(page.locator('a[href="/console/tenants"]')).toHaveCount(0, { timeout: 5000 })
    await page.goto(WEB_URL + '/console/tenants')
    await page.waitForLoadState('networkidle')
    expect(page.url()).not.toContain('/console/tenants')
  })

  test('筛选、菜单高亮与滚动壳', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'desktop') testInfo.skip()
    await page.goto(WEB_URL + '/login')
    await page.fill('#username', 'admin')
    await page.fill('#password', 'Admin@2026!')
    await page.click('button:has-text("登录")')
    await page.waitForURL(/\/console/, { timeout: 10000 })
    // "概览"高亮
    await expect(page.locator('.console > aside nav a.active')).toContainText('概览')
    // 点租户管理
    await page.click('a[href="/console/tenants"]')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.console > aside nav a.active')).toContainText('租户管理')
    // 筛选
    await page.locator('select').first().selectOption('SELF_OPERATED')
    await page.waitForLoadState('networkidle')
    await page.fill('.search-box input', 'default')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table tbody tr').first()).toContainText('default')
    // 滚动测试
    const before = await page.locator('.console > aside').boundingBox()
    await page.locator('.console-main > main').evaluate(el => el.scrollTo(0, 200))
    await page.waitForTimeout(300)
    const after = await page.locator('.console > aside').boundingBox()
    expect(after?.y).toBe(before?.y)
  })
})

test.describe('通用验收（全视口）', () => {
  test('未登录跳转登录', async ({ page }) => {
    await page.goto(WEB_URL + '/console/overview')
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
  })

  test('主题切换与持久化', async ({ page }) => {
    await page.goto(WEB_URL + '/login')
    const toggle = page.locator('.theme-toggle').first()
    await expect(toggle).toBeVisible()
    await toggle.click()
    const t1 = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
    expect(['light', 'dark']).toContain(t1)
    await page.reload()
    const t2 = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
    expect(t2).toBe(t1)
  })
})

test.describe('视口响应式', () => {
  for (const vp of [{ w: 1440, h: 900 }, { w: 768, h: 1024 }, { w: 390, h: 844 }]) {
    test(`${vp.w}x${vp.h} 登录页可用`, async ({ page }) => {
      await page.setViewportSize({ width: vp.w, height: vp.h })
      await page.goto(WEB_URL + '/login')
      await expect(page.locator('.login-form')).toBeVisible()
      const ok = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
      expect(ok).toBeTruthy()
    })
  }
})
