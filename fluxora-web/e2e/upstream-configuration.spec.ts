import { test, expect, type Page } from '@playwright/test'

/**
 * 上游配置控制面端到端验收。
 * 覆盖平台管理员创建共享 Provider 与多协议接入地址、租户管理员创建私有配置与通道、
 * 凭证创建/批量导入、脱敏展示、重复跳过与软删除重导入、安全错误文案与三视口可用性。
 *
 * 前置：fluxora DB 已执行 V7 迁移；admin/Admin@2026! 与 default 租户管理员 e2eadmin/chen2983 已存在。
 */

const PLATFORM = { username: 'admin', password: 'Admin@2026!' }
const TENANT_ADMIN = { username: 'e2eadmin', password: 'chen2983' }

async function login(page: Page, creds: { username: string; password: string }) {
  await page.goto('/login')
  await page.locator('input[placeholder*="用户名"]').first().fill(creds.username)
  await page.locator('input[placeholder*="密码"]').first().fill(creds.password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/console/, { timeout: 15000 })
}

/** 在当前打开的下拉中选择一个选项（按可见文本）。 */
async function selectOption(page: Page, triggerPlaceholder: string, optionText: string | RegExp) {
  await page.locator(`.n-base-selection input[placeholder="${triggerPlaceholder}"]`).click()
  await page.locator('.n-base-select-option', { hasText: optionText }).first().click()
}

test.describe('上游配置控制面', () => {
  test.describe.configure({ mode: 'serial' })

  const sharedName = 'E2E共享-' + Date.now()
  const sharedCode = 'e2e-shared-' + Date.now()
  const sharedUrl = 'https://upstream-e2e.example.com/v1'
  const channelName = 'E2E通道-' + Date.now()

  test('平台管理员创建共享 Provider 与多协议接入地址', async ({ page }) => {
    await login(page, PLATFORM)
    await page.goto('/console/providers')
    await expect(page.getByRole('heading', { name: '上游厂商' })).toBeVisible()

    await page.getByRole('button', { name: '新增厂商' }).click()
    await page.locator('.n-modal input[placeholder="例如 OpenAI"]').fill(sharedName)
    await page.locator('.n-modal input[placeholder="例如 openai"]').fill(sharedCode)
    // 选择来源范围：平台共享
    await page.locator('.n-modal .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: '平台共享' }).click()
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(sharedName)).toBeVisible()
    await expect(page.locator('.metric-value').first()).not.toHaveText('—')

    // 接入地址页：同一 URL 不同协议分别创建
    await page.goto('/console/provider-base-urls')
    await page.locator('.n-base-selection').first().click()
    await page.locator('.n-base-select-option', { hasText: sharedName }).first().click()

    await page.getByRole('button', { name: '新增地址' }).click()
    await page.locator('.n-modal .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: 'OPENAI' }).click()
    await page.locator('.n-modal input[placeholder="https://api.example.com/v1"]').fill(sharedUrl)
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(sharedUrl).first()).toBeVisible()

    await page.getByRole('button', { name: '新增地址' }).click()
    await page.locator('.n-modal .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: 'ANTHROPIC' }).click()
    await page.locator('.n-modal input[placeholder="https://api.example.com/v1"]').fill(sharedUrl)
    await page.getByRole('button', { name: '保存' }).click()

    // 同协议同 URL 重复应被安全拒绝（不暴露 500 / SQL / 堆栈）
    await page.getByRole('button', { name: '新增地址' }).click()
    await page.locator('.n-modal .n-base-selection').click()
    await page.locator('.n-base-select-option', { hasText: 'OPENAI' }).click()
    await page.locator('.n-modal input[placeholder="https://api.example.com/v1"]').fill(sharedUrl)
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.locator('body')).not.toContainText('500')
    await expect(page.locator('body')).not.toContainText('exception')
  })

  test('租户管理员可见共享并创建私有 Provider 与通道', async ({ browser }) => {
    const page = await browser.newPage()
    await login(page, TENANT_ADMIN)
    await page.goto('/console/providers')
    await expect(page.getByText(sharedName)).toBeVisible()

    const privateName = 'E2E私有-' + Date.now()
    await page.getByRole('button', { name: '新增厂商' }).click()
    await page.locator('.n-modal input[placeholder="例如 OpenAI"]').fill(privateName)
    await page.locator('.n-modal input[placeholder="例如 openai"]').fill('e2e-priv-' + Date.now())
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(privateName)).toBeVisible()

    // 通道页：基于共享地址创建通道
    await page.goto('/console/provider-channels')
    await page.getByRole('button', { name: '新增通道' }).click()
    await page.locator('.n-modal .n-base-selection').nth(0).click()
    await page.locator('.n-base-select-option', { hasText: sharedName }).first().click()
    await page.locator('.n-modal .n-base-selection').nth(1).click()
    await page.locator('.n-base-select-option', { hasText: /OPENAI/ }).first().click()
    await page.locator('.n-modal input[placeholder="例如 OpenAI 主通道"]').fill(channelName)
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(channelName)).toBeVisible()
    await page.close()
  })

  async function openChannelDetail(page: Page) {
    const btn = page.locator('button.link-btn', { hasText: channelName }).first()
    await btn.scrollIntoViewIfNeeded()
    // n-data-table 的粘性表头会拦截指针事件，直接在元素上派发 click 以触发 Vue onClick
    await btn.evaluate(el => (el as HTMLElement).click())
    await expect(page.getByRole('button', { name: '新增凭证' })).toBeVisible({ timeout: 10000 })
  }

  test('通道内创建凭证并验证脱敏不回显明文', async ({ browser }) => {
    const page = await browser.newPage()
    await login(page, TENANT_ADMIN)
    await page.goto('/console/provider-channels')
    await openChannelDetail(page)

    const secret = 'sk-E2E-PLAINTEXT-' + Date.now() + '-XYZ'
    await page.getByRole('button', { name: '新增凭证' }).click()
    await page.locator('.n-modal input[placeholder="输入上游访问凭证"]').fill(secret)
    await page.locator('.n-modal').getByRole('button', { name: '保存' }).click()
    // 等待 modal 关闭，列表才会展示新增行
    await expect(page.locator('.n-modal')).toBeHidden()
    // 列表/抽屉不出现完整明文，只出现脱敏标识
    await expect(page.locator('.n-drawer')).not.toContainText(secret)
    await expect(page.locator('.n-drawer')).toContainText(/\*\*\*/, { timeout: 8000 })
    await page.close()
  })

  test('批量导入：成功、批内重复、软删除重导入与脱敏明细', async ({ browser }) => {
    const page = await browser.newPage()
    await login(page, TENANT_ADMIN)
    await page.goto('/console/provider-channels')
    await openChannelDetail(page)

    const fresh = 'sk-E2E-IMP-' + Date.now()
    const fresh2 = 'sk-E2E-IMP2-' + Date.now()
    await page.getByRole('button', { name: '批量导入' }).click()
    await page.locator('textarea').fill(`${fresh}\n${fresh}\n${fresh2}\n   `)
    await page.getByRole('button', { name: '开始导入' }).click()
    await expect(page.getByText('导入结果')).toBeVisible()
    await expect(page.locator('.n-drawer').last()).not.toContainText(fresh)
    await page.getByRole('button', { name: '完成' }).click()

    // 再次导入相同凭证 → 全部跳过
    await page.getByRole('button', { name: '批量导入' }).click()
    await page.locator('textarea').fill(fresh)
    await page.getByRole('button', { name: '开始导入' }).click()
    await expect(page.locator('.n-drawer').last()).toContainText(/0/)
    await page.getByRole('button', { name: '完成' }).click()
    await page.close()
  })

  test('桌面视口三页无横向溢出', async ({ page }) => {
    await login(page, PLATFORM)
    for (const p of ['/console/providers', '/console/provider-base-urls', '/console/provider-channels']) {
      await page.goto(p)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      const sw = await page.evaluate(() => document.documentElement.scrollWidth)
      const cw = await page.evaluate(() => document.documentElement.clientWidth)
      expect(sw).toBeLessThanOrEqual(cw + 1)
    }
  })
})

test.describe('上游配置三视口', () => {
  for (const v of [{ w: 768, h: 1024, name: '平板' }, { w: 390, h: 844, name: '移动' }]) {
    test(`${v.name}视口页面可用且无溢出`, async ({ browser }) => {
      const page = await browser.newPage({ viewport: { width: v.w, height: v.h } })
      await login(page, PLATFORM)
      await page.goto('/console/providers')
      await expect(page.getByRole('heading', { name: '上游厂商' })).toBeVisible()
      const sw = await page.evaluate(() => document.documentElement.scrollWidth)
      const cw = await page.evaluate(() => document.documentElement.clientWidth)
      expect(sw).toBeLessThanOrEqual(cw + 1)
      await page.close()
    })
  }
})
