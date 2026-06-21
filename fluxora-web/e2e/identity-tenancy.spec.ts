import { expect, test } from '@playwright/test'

const WEB_URL = 'http://localhost:5173'

test.describe('身份认证与租户管理真实验收', () => {
  test('平台管理员登录 → 自营初始化 → 租户CRUD → 自营管理员无权限', async ({ page }) => {
    // ===== 1. 登录页 =====
    await page.goto(WEB_URL + '/login')
    await expect(page.locator('.login-card')).toBeVisible()

    // ===== 2. 错误密码不暴露技术信息 =====
    await page.fill('#username', 'admin')
    await page.fill('#password', 'wrong-password')
    await page.click('button[type="submit"]')
    await expect(page.locator('.error-msg')).toBeVisible({ timeout: 5000 })
    const errorText = await page.locator('.error-msg').textContent()
    expect(errorText).not.toContain('401')
    expect(errorText).not.toContain('Exception')
    expect(errorText).not.toContain('SQL')

    // ===== 3. 正确登录 =====
    await page.fill('#username', 'admin')
    await page.fill('#password', 'Admin@2026!')
    await page.click('button[type="submit"]')
    await page.waitForURL(/\/console\/(setup|overview)/, { timeout: 10000 })

    // ===== 4. 如首次，完成初始化 =====
    if (page.url().includes('/console/setup')) {
      await expect(page.locator('.setup-card')).toBeVisible()
      await page.fill('#tenantName', 'Fluxora 自营')
      await page.fill('#adminUser', 'e2eadmin')
      await page.fill('#adminPass', 'e2epass123')
      await page.fill('#adminDisplay', 'E2E 管理员')
      await page.click('button[type="submit"]')
      await page.waitForURL(/\/console\/(setup|overview)/, { timeout: 10000 })
      const btn = page.locator('button.primary:has-text("进入控制台")')
      await btn.waitFor({ state: 'visible', timeout: 8000 })
      await btn.click()
    }

    // ===== 5. 确认在控制台 =====
    await page.waitForURL(/\/console/, { timeout: 10000 })
    await expect(page.locator('.console')).toBeVisible({ timeout: 8000 })

    // ===== 6. 进入租户管理 =====
    await expect(page.locator('a[href="/console/tenants"]')).toBeVisible({ timeout: 5000 })
    await page.click('a[href="/console/tenants"]')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-mgmt')).toBeVisible({ timeout: 5000 })

    // ===== 7. 创建 STANDARD 租户 =====
    const tenantCode = 'e2e-' + Date.now()
    await page.click('button:has-text("新增租户")')
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    await page.locator('.dialog input').nth(0).fill(tenantCode)
    await page.locator('.dialog input').nth(1).fill('E2E 测试租户')
    await page.locator('.dialog button:has-text("创建")').click()
    await expect(page.locator('.dialog')).not.toBeVisible({ timeout: 8000 })

    // ===== 8. 搜索验证 =====
    await page.fill('.search-box input', tenantCode)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table tbody tr')).toHaveCount(1, { timeout: 5000 })
    await expect(page.locator('.tenant-table tbody tr').first()).toContainText(tenantCode)

    // ===== 9. 编辑 =====
    await page.locator('.tenant-table tbody tr').first().locator('button[title="编辑"]').click()
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    // 编辑弹窗：第一个 input 是名称字段
    await page.locator('.dialog input').first().fill('E2E 已编辑')
    await page.locator('.dialog button:has-text("保存")').click()
    await expect(page.locator('.dialog')).not.toBeVisible({ timeout: 8000 })
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table tbody tr').first()).toContainText('E2E 已编辑')

    // ===== 10. 停用 =====
    await page.locator('.tenant-table tbody tr').first().locator('button.danger').first().click()
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    await page.locator('.dialog button:has-text("确认停用")').click()
    await expect(page.locator('.dialog')).not.toBeVisible({ timeout: 5000 })

    // ===== 11. 启用 =====
    await page.waitForLoadState('networkidle')
    await page.locator('.tenant-table tbody tr').first().locator('button').filter({ hasText: /▶|启用/ }).click()
    await page.waitForLoadState('networkidle')

    // ===== 12. 删除 =====
    await page.locator('.tenant-table tbody tr').first().locator('button[title="删除"]').click()
    await expect(page.locator('.dialog')).toBeVisible({ timeout: 3000 })
    await page.locator('.dialog button:has-text("确认删除")').click()
    await expect(page.locator('.dialog')).not.toBeVisible({ timeout: 5000 })

    // ===== 13. 确认已删除 =====
    await page.fill('.search-box input', tenantCode)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.empty-state')).toBeVisible({ timeout: 5000 })

    // ===== 14. 退出 → 自营管理员登录 → 无租户管理 =====
    await page.locator('.logout-btn').click({ force: true })
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.login-card')).toBeVisible({ timeout: 5000 })

    await page.fill('#username', 'e2eadmin')
    await page.fill('#password', 'e2epass123')
    await page.click('button[type="submit"]')
    await page.waitForURL(/\/console/, { timeout: 10000 })

    // 租户管理员看不到"租户管理"菜单
    await expect(page.locator('a[href="/console/tenants"]')).toHaveCount(0, { timeout: 5000 })

    // 直接访问被拒绝
    await page.goto(WEB_URL + '/console/tenants')
    await page.waitForLoadState('networkidle')
    expect(page.url()).not.toContain('/console/tenants')
  })

  test('筛选：类型、状态、过期、重置', async ({ page }) => {
    // 快速登录
    await page.goto(WEB_URL + '/login')
    await page.fill('#username', 'admin')
    await page.fill('#password', 'Admin@2026!')
    await page.click('button[type="submit"]')
    await page.waitForURL(/\/console/, { timeout: 10000 })

    // 进入租户管理
    await page.click('a[href="/console/tenants"]')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-mgmt')).toBeVisible({ timeout: 5000 })

    // 类型筛选
    await page.locator('select').first().selectOption('SELF_OPERATED')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table tbody tr')).toHaveCount(1, { timeout: 5000 })

    // 状态筛选
    await page.locator('select').nth(1).selectOption('ENABLED')
    await page.waitForLoadState('networkidle')

    // 关键词搜索
    await page.fill('.search-box input', 'default')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.tenant-table tbody tr').first()).toContainText('default')

    // 重置
    await page.locator('button:has-text("重置")').click({ force: true })
    await page.waitForLoadState('networkidle')
  })

  test('未登录访问控制台应跳转登录', async ({ page }) => {
    await page.goto(WEB_URL + '/console/overview')
    await page.waitForLoadState('networkidle')
    expect(page.url()).toContain('/login')
  })
})

test.describe('视口响应式', () => {
  for (const vp of [{ w: 1440, h: 900 }, { w: 768, h: 1024 }, { w: 390, h: 844 }]) {
    test(`登录页 ${vp.w}x${vp.h} 无横向溢出`, async ({ page }) => {
      await page.setViewportSize({ width: vp.w, height: vp.h })
      await page.goto(WEB_URL + '/login')
      await expect(page.locator('.login-card')).toBeVisible()
      const ok = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
      expect(ok).toBeTruthy()
    })
  }
})
