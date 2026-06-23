import { expect, test } from '@playwright/test'

const WEB_URL = 'http://localhost:5173'

test.describe('真实验收（桌面端）', () => {
  test('平台管理员登录 → 自营初始化 → 租户创建与搜索', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'desktop') testInfo.skip()

    await page.goto(WEB_URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('wrong-password')
    await page.locator('button:has-text("登录")').click()
    await expect(page.locator('.n-message')).toBeVisible({ timeout: 5000 })

    await page.locator('input[placeholder*="用户名"]').first().fill('admin')
    await page.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await page.locator('button:has-text("登录")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })

    if (page.url().includes('/console/setup')) {
      // 两步向导：通过 n-form-item label 定位输入框
      await page.locator('.n-form-item:has(.n-form-item-label:text("租户名称")) input').fill('Fluxora 自营')
      await page.locator('button:has-text("下一步")').click()
      await page.waitForTimeout(300) // 等 step 过渡
      await page.locator('.n-form-item:has(.n-form-item-label:text("管理员用户名")) input').fill('e2eadmin')
      await page.locator('.n-form-item:has(.n-form-item-label:text("管理员显示名")) input').fill('E2E 管理员')
      await page.locator('.n-form-item:has(.n-form-item-label:text("管理员密码")) input').fill('Admin@2026!')
      await page.locator('.n-form-item:has(.n-form-item-label:text("确认密码")) input').fill('Admin@2026!')
      await page.locator('button:has-text("创建并进入控制台")').click()
      await page.waitForURL(/\/console\/overview/, { timeout: 10000 })
    }

    await expect(page.locator('[data-testid="console-content"]')).toBeVisible({ timeout: 8000 })
    await page.locator('.n-menu-item-content:has-text("租户管理")').click()
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.n-data-table')).toBeVisible({ timeout: 5000 })

    const tenantCode = 'e2e-' + Date.now()
    await page.locator('button:has-text("新增租户")').click()
    await expect(page.locator('.tenant-modal').last()).toBeVisible({ timeout: 3000 })
    await page.locator('.tenant-modal').last().locator('.n-input input').first().fill(tenantCode)
    await page.locator('.tenant-modal').last().locator('.n-input input').nth(1).fill('E2E 测试租户')
    await page.locator('.tenant-modal button:has-text("创建租户")').click()
    await expect(page.locator('.tenant-modal').last()).not.toBeVisible({ timeout: 8000 })

    await page.waitForTimeout(500) // 等列表刷新完成
    await page.locator('input[placeholder*="搜索"]').first().fill(tenantCode)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.n-data-table-tr').first()).toBeVisible({ timeout: 5000 })
  })

  test('自营管理员无租户管理权限', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'desktop') testInfo.skip()
    // 使用独立的新浏览器上下文，避免 Cookie/状态污染
    const ctx = await page.context().browser()!.newContext()
    const p = await ctx.newPage()
    await p.goto(WEB_URL + '/login')
    await p.locator('input[placeholder*="用户名"]').first().fill('e2eadmin')
    await p.locator('input[placeholder*="密码"]').first().fill('Admin@2026!')
    await p.locator('button:has-text("登录")').click()
    await p.waitForURL(/\/console/, { timeout: 10000 })
    await expect(p.locator('.n-menu-item-content:has-text("租户管理")')).toHaveCount(0, { timeout: 5000 })
    await p.goto(WEB_URL + '/console/tenants')
    await p.waitForLoadState('networkidle')
    expect(p.url()).not.toContain('/console/tenants')
    await ctx.close()
  })

  test('主题切换持久化', async ({ page }) => {
    test.skip(test.info().project.name !== 'desktop', '响应式由专门用例覆盖')
    await page.goto(WEB_URL + '/login')
    const toggle = page.locator('button[aria-label^="切换为"]').first()
    await expect(toggle).toBeVisible({ timeout: 3000 })
    await toggle.click()
    const t1 = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
    expect(['light', 'dark']).toContain(t1)
    await page.reload()
    const t2 = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
    expect(t2).toBe(t1)
  })
})

test.describe('通用验收（全视口）', () => {
  test('未登录跳转登录', async ({ page }) => {
    await page.goto(WEB_URL + '/console/overview')
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
  })
})

test.describe('视口响应式', () => {
  for (const vp of [{ w: 1440, h: 900 }, { w: 768, h: 1024 }, { w: 390, h: 844 }]) {
    test(`${vp.w}x${vp.h} 可用`, async ({ page }) => {
      await page.setViewportSize({ width: vp.w, height: vp.h })
      await page.goto(WEB_URL + '/login')
      await expect(page.locator('input[placeholder*="用户名"]')).toBeVisible()
      const ok = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
      expect(ok).toBeTruthy()
    })
  }
})
