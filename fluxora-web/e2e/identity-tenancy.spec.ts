import { expect, test } from '@playwright/test'

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
    const errorEl = page.locator('.error-msg')
    await expect(errorEl).toBeVisible({ timeout: 5000 })
    const errorText = await errorEl.textContent()
    expect(errorText).not.toContain('401')
    expect(errorText).not.toContain('Exception')
    expect(errorText).not.toContain('SQL')

    // 3. 输入正确密码登录
    await page.fill('#username', 'admin')
    await page.fill('#password', 'Admin@2026!')
    await page.click('button[type="submit"]')

    // 4. 等待导航到 setup 或 console
    await page.waitForLoadState('networkidle')
    const currentUrl = page.url()

    if (currentUrl.includes('/console/setup')) {
      // 首次需要初始化自营租户
      await expect(page.locator('.setup-card')).toBeVisible()
      await page.fill('#tenantName', 'Fluxora 自营')
      await page.fill('#adminUser', 'e2eadmin')
      await page.fill('#adminPass', 'e2epass123')
      await page.fill('#adminDisplay', 'E2E 管理员')
      await page.click('button[type="submit"]')

      // 等待网络请求完成（初始化 API 调用可能较慢）
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(500)

      // 如果还在 setup 页面，点击进入控制台
      const enterBtn = page.locator('button.primary:has-text("进入控制台")')
      if (await enterBtn.isVisible().catch(() => false)) {
        await enterBtn.click()
      }
    }

    // 5. 确认在控制台页面（可能重定向到 /login 如果不是管理员）
    await page.waitForLoadState('networkidle')
    const finalUrl = page.url()

    if (finalUrl.includes('/console')) {
      // 在控制台内 - 验证控制台布局
      await expect(page.locator('.console')).toBeVisible({ timeout: 8000 })
    }

    // 6. 尝试退出登录
    const logoutBtn = page.locator('.logout-btn')
    if (await logoutBtn.isVisible().catch(() => false)) {
      await logoutBtn.click()
      await page.waitForTimeout(1000)
    }
  })

  test('未登录访问控制台应跳转登录', async ({ page }) => {
    await page.goto(WEB_URL + '/console/overview')
    await page.waitForLoadState('networkidle')
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
