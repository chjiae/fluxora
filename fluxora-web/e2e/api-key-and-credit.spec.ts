import { expect, test, type Page } from '@playwright/test'

/**
 * API Key + 额度 E2E。
 *
 * 前提：先启动 PostgreSQL / fluxora-platform / fluxora-web。
 * 桌面专测主流程；三视口仅做最低限度可见性 + 横向无溢出基线。
 */

const WEB_URL = 'http://localhost:5173'
const ADMIN_USERNAME = 'admin'
const ADMIN_PASSWORD = 'Admin@2026!'

const RUN = Date.now().toString().slice(-7)
const E2E_USER = `e2e_apikey_${RUN}`
const E2E_USER_PWD = 'Pwd2026Strong'

async function login(page: Page, username: string, password: string) {
  await page.goto(WEB_URL + '/login')
  await page.locator('input[placeholder*="用户名"]').first().fill(username)
  await page.locator('input[placeholder*="密码"]').first().fill(password)
  await page.locator('button:has-text("登录")').click()
  await page.waitForURL(/\/console/, { timeout: 10000 })
}

async function logout(page: Page) {
  await page.locator('button:has-text("· 退出")').click()
  await page.locator('.n-dropdown-option:has-text("退出登录")').click()
  await page.waitForURL(/\/login/, { timeout: 5000 })
}

async function ensureSelfOperated(page: Page) {
  if (page.url().includes('/console/setup')) {
    await page.locator('.n-input input').nth(0).fill('Fluxora 自营')
    await page.locator('.n-input input').nth(1).fill('e2eadmin')
    await page.locator('.n-input input').nth(2).fill('e2epass1234')
    await page.locator('.n-input input').nth(3).fill('E2E 管理员')
    await page.locator('button:has-text("创建自营租户")').click()
    await page.waitForURL(/\/console/, { timeout: 10000 })
    const enter = page.locator('button:has-text("进入控制台")')
    if (await enter.isVisible({ timeout: 3000 }).catch(() => false)) await enter.click()
  }
}

test.describe('API Key + 额度 E2E（桌面）', () => {
  test.describe.configure({ mode: 'serial' })

  test('平台管理员通过成员管理创建一个普通用户', async ({ page }, info) => {
    if (info.project.name !== 'desktop') info.skip()
    await login(page, ADMIN_USERNAME, ADMIN_PASSWORD)
    await ensureSelfOperated(page)

    await page.locator('.n-menu-item-content:has-text("租户管理")').click()
    await page.waitForLoadState('networkidle')
    await page.locator('input[placeholder*="搜索租户"]').fill('default')
    await page.waitForTimeout(500)
    await page.locator('.tenant-table .row-kebab').first().click()
    await page.locator('.n-dropdown-option:has-text("管理成员")').click()
    await page.waitForURL(/\/console\/tenants\/\d+\/members/, { timeout: 5000 })

    await page.locator('button:has-text("新增成员")').first().click()
    const modal = page.locator('.member-modal').last()
    await modal.locator('.n-input input').nth(0).fill(E2E_USER)
    await modal.locator('.n-input input').nth(1).fill('E2E API Key 测试用户')
    await modal.locator('.n-input input').nth(2).fill(E2E_USER + '@e2e.local')
    await modal.locator('.n-base-selection').click()
    await page.locator('.n-base-select-option:has-text("成员")').click()
    await modal.locator('input[type="password"]').nth(0).fill(E2E_USER_PWD)
    await modal.locator('input[type="password"]').nth(1).fill(E2E_USER_PWD)
    await modal.locator('button:has-text("创建成员")').click()
    await expect(page.locator('.n-message:has-text("成员已创建")')).toBeVisible({ timeout: 5000 })
  })

  test('普通用户创建 API Key → 一次性展示 + 复制 + 关闭后不可恢复', async ({ page }, info) => {
    if (info.project.name !== 'desktop') info.skip()
    await login(page, E2E_USER, E2E_USER_PWD)

    // 进入「我的 API Key」
    await page.locator('.n-menu-item-content:has-text("我的 API Key")').click()
    await page.waitForURL(/\/console\/api-keys/, { timeout: 5000 })

    await page.locator('button:has-text("新建 Key")').first().click()
    const createModal = page.locator('.key-modal').last()
    await createModal.locator('.n-input input').first().fill('e2e-key-' + RUN)
    await createModal.locator('button:has-text("创建")').click()

    // 一次性展示弹窗出现
    const reveal = page.locator('.key-reveal-modal')
    await expect(reveal).toBeVisible({ timeout: 5000 })
    await expect(reveal).toContainText('该 API Key 仅展示一次')

    // 完整 Key 应以 flx_ 开头并被 monospace 渲染
    const keyText = await reveal.locator('.reveal-key-text').textContent()
    expect(keyText).toBeTruthy()
    expect(keyText!).toMatch(/^flx_[A-Za-z0-9_-]{8}_[A-Za-z0-9_-]{32}$/)

    // 复制按钮可点击
    await reveal.locator('button:has-text("复制")').click()
    await expect(reveal.locator('button:has-text("已复制")')).toBeVisible({ timeout: 3000 })

    // 关闭弹窗
    await reveal.locator('button:has-text("我已妥善保存")').click()
    await expect(reveal).not.toBeVisible({ timeout: 3000 })

    // 列表中只显示前缀，不显示完整 plaintext
    await page.waitForTimeout(500)
    const html = await page.content()
    expect(html).toContain('flx_')           // 前缀仍可见
    // 完整 plaintext 的密钥段不应再出现
    const secret = keyText!.split('_').slice(2).join('_')
    expect(html.includes(secret)).toBeFalsy()
  })

  test('普通用户编辑 / 停用 / 启用 / 删除自己的 Key', async ({ page }, info) => {
    if (info.project.name !== 'desktop') info.skip()
    await login(page, E2E_USER, E2E_USER_PWD)
    await page.locator('.n-menu-item-content:has-text("我的 API Key")').click()
    await page.waitForURL(/\/console\/api-keys/, { timeout: 5000 })

    // 进入第一行管理
    await page.locator('.key-table .n-data-table-tr').first().click()
    await page.locator('button:has-text("管理")').first().click()
    // 停用
    await page.locator('.manage-section:has-text("状态") button:has-text("停用")').click()
    await page.locator('button:has-text("确认停用")').click()
    await expect(page.locator('.n-message:has-text("已停用")')).toBeVisible({ timeout: 5000 })
    // 启用
    await page.locator('.manage-section:has-text("状态") button:has-text("启用")').click()
    await expect(page.locator('.n-message:has-text("已启用")')).toBeVisible({ timeout: 5000 })
    // 删除
    await page.locator('.danger-section button:has-text("删除 Key")').click()
    await page.locator('button:has-text("确认删除")').click()
    await expect(page.locator('.n-message:has-text("已删除")')).toBeVisible({ timeout: 5000 })
  })

  test('普通用户的「我的额度」页面可见', async ({ page }, info) => {
    if (info.project.name !== 'desktop') info.skip()
    await login(page, E2E_USER, E2E_USER_PWD)
    await page.locator('.n-menu-item-content:has-text("我的额度")').click()
    await page.waitForURL(/\/console\/credit/, { timeout: 5000 })
    await expect(page.locator('.credit-page')).toBeVisible({ timeout: 5000 })
    await expect(page.locator('h1:has-text("我的额度")')).toBeVisible()
  })

  test('租户管理员调整普通用户额度', async ({ page }, info) => {
    if (info.project.name !== 'desktop') info.skip()
    await login(page, 'e2eadmin', 'e2epass1234')
    await page.locator('.n-menu-item-content:has-text("额度管理")').click()
    await page.waitForURL(/\/console\/credit\/manage/, { timeout: 5000 })

    // 找到目标用户的「增加」按钮
    await page.locator(`.user-table .n-data-table-tr:has-text("${E2E_USER}") button:has-text("增加")`).first().click()
    const adjustModal = page.locator('.credit-modal')
    await expect(adjustModal).toBeVisible({ timeout: 3000 })
    await adjustModal.locator('.n-input input').first().fill('50')
    await adjustModal.locator('textarea').fill('e2e 测试初始化')
    await adjustModal.locator('button:has-text("提交")').click()
    await expect(page.locator('.n-message:has-text("增加")')).toBeVisible({ timeout: 5000 })

    // 扣减 200 应当被余额不足拒绝
    await page.locator(`.user-table .n-data-table-tr:has-text("${E2E_USER}") button:has-text("扣减")`).first().click()
    await expect(adjustModal).toBeVisible({ timeout: 3000 })
    await adjustModal.locator('.n-input input').first().fill('200')
    await adjustModal.locator('textarea').fill('overflow test')
    await adjustModal.locator('button:has-text("提交")').click()
    // 二次确认对话框
    await page.locator('button:has-text("确认扣减")').click()
    await expect(page.locator('.n-message:has-text("额度不足")')).toBeVisible({ timeout: 5000 })
  })

  test('所有失败提示不含 HTTP/SQL/堆栈技术细节', async ({ page }, info) => {
    if (info.project.name !== 'desktop') info.skip()
    await page.goto(WEB_URL + '/login')
    await page.locator('input[placeholder*="用户名"]').first().fill('nosuchuser')
    await page.locator('input[placeholder*="密码"]').first().fill('wrong')
    await page.locator('button:has-text("登录")').click()
    await expect(page.locator('.n-message')).toBeVisible({ timeout: 5000 })
    const txt = (await page.locator('.n-message').textContent()) || ''
    for (const tech of ['401', '500', 'AUTH_', 'SQL', 'stack', 'Exception']) {
      expect(txt).not.toContain(tech)
    }
  })
})

test.describe('视口响应式（API Key / 额度）', () => {
  for (const vp of [{ w: 1440, h: 900 }, { w: 768, h: 1024 }, { w: 390, h: 844 }]) {
    test(`${vp.w}x${vp.h} 登录可达`, async ({ page }) => {
      await page.setViewportSize({ width: vp.w, height: vp.h })
      await page.goto(WEB_URL + '/login')
      await expect(page.locator('input[placeholder*="用户名"]')).toBeVisible()
      const noOverflow = await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      )
      expect(noOverflow).toBeTruthy()
    })
  }
})
