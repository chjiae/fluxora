import { expect, test } from '@playwright/test'

const PLATFORM_URL = 'http://localhost:8080'
const WEB_URL = 'http://localhost:5173'

test.describe('身份认证与租户管理全流程', () => {
  test('平台管理员登录 → 自营初始化 → 租户管理 → 退出', async ({ page }) => {
    // 1. 访问登录页
    await page.goto(WEB_URL + '/login')
    await expect(page.locator('.login-card')).toBeVisible()

    // 2. 输入错误密码，验证不显示技术错误
    await page.fill('#username', 'admin')
    await page.fill('#password', 'wrong-password')
    await page.click('button[type="submit"]')
    // 等待错误提示出现（而非技术文本）
    const errorEl = page.locator('.error-msg')
    await expect(errorEl).toBeVisible({ timeout: 5000 })
    const errorText = await errorEl.textContent()
    expect(errorText).not.toContain('401')
    expect(errorText).not.toContain('UNAUTHORIZED')
    expect(errorText).not.toContain('Exception')
    expect(errorText).not.toContain('SQL')

    // 3. 输入正确密码登录
    await page.fill('#username', 'admin')
    await page.fill('#password', 'admin123')
    await page.click('button[type="submit"]')

    // 4. 检查是否跳转到初始化向导（首次）或控制台
    await page.waitForTimeout(2000)
    const url = page.url()
    if (url.includes('/console/setup')) {
      // 首次需要初始化自营租户
      await expect(page.locator('.setup-card')).toBeVisible()
      await page.fill('#tenantName', 'Fluxora 自营')
      await page.fill('#adminUser', 'e2eadmin')
      await page.fill('#adminPass', 'e2epass123')
      await page.fill('#adminDisplay', 'E2E 管理员')
      await page.click('button[type="submit"]')
      await page.waitForTimeout(2000)
      // 点击进入控制台
      await page.click('button.primary')
    }

    // 5. 确认进入控制台
    await page.waitForTimeout(1500)
    await expect(page.locator('.console')).toBeVisible()

    // 6. 如果平台管理员，进入租户管理
    if (url.includes('/console/setup') || url.includes('/console')) {
      // 导航到租户管理
      const tenantLink = page.locator('a[href="/console/tenants"]')
      if (await tenantLink.isVisible()) {
        await tenantLink.click()
        await page.waitForTimeout(2000)

        // 确认租户管理页面加载
        await expect(page.locator('.tenant-mgmt')).toBeVisible({ timeout: 5000 })

        // 搜索自营租户
        const searchInput = page.locator('.search-box input')
        if (await searchInput.isVisible()) {
          await searchInput.fill('default')
          await page.waitForTimeout(1000)
        }
      }
    }

    // 7. 退出登录
    const logoutBtn = page.locator('.logout-btn')
    if (await logoutBtn.isVisible()) {
      await logoutBtn.click()
      await page.waitForTimeout(1000)
      // 确认跳转到登录页
      await expect(page.locator('.login-card')).toBeVisible({ timeout: 5000 })
    }
  })

  test('未登录访问控制台应跳转登录', async ({ page }) => {
    await page.goto(WEB_URL + '/console/overview')
    await page.waitForTimeout(2000)
    // 应该重定向到登录页
    expect(page.url()).toContain('/login')
  })
})

test.describe('视口响应式检查', () => {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 768, height: 1024 },
    { width: 390, height: 844 },
  ]) {
    test(`登录页在 ${viewport.width}x${viewport.height} 无横向溢出`, async ({ page }) => {
      await page.setViewportSize(viewport)
      await page.goto(WEB_URL + '/login')
      await expect(page.locator('.login-card')).toBeVisible()
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      )
      expect(overflow).toBeTruthy()
    })
  }
})
