import { expect, test, type Page } from '@playwright/test'

/** 模型控制面真实浏览器验收：运行时使用临时数据库与 MODEL_DISCOVERY_MOCK_ENABLED=true。 */
const run = Date.now().toString().slice(-7)

async function loginAndInitialize(page: Page) {
  await page.goto('/login')
  await page.locator('input[placeholder*="用户名"]').fill('admin')
  await page.locator('input[placeholder*="密码"]').fill('Admin@2026!')
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL(/\/console\/(setup|overview)/, { timeout: 15000 })
  if (page.url().includes('/console/setup')) {
    await page.locator('.setup-form input').nth(0).fill('模型验收租户')
    await page.getByRole('button', { name: '下一步' }).click()
    await page.locator('.setup-form input').nth(0).fill(`modeladmin${run}`)
    await page.locator('.setup-form input').nth(1).fill('模型验收管理员')
    await page.locator('.setup-form input').nth(2).fill('ModelPwd2026!')
    await page.locator('.setup-form input').nth(3).fill('ModelPwd2026!')
    await page.getByRole('button', { name: '创建并进入控制台' }).click()
    await page.waitForURL(/\/console\/overview/, { timeout: 15000 })
  }
}

test.describe('模型目录与租户发布', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(90000)
  const modelName = `E2E 模型 ${run}`
  const modelCode = `e2e-model-${run}`

  test('平台管理员创建模型、设置八位精度价格并发布为租户草稿', async ({ page }) => {
    await loginAndInitialize(page)
    await page.goto('/console/platform-models')
    await expect(page.getByRole('heading', { name: '平台模型库' })).toBeVisible()
    await page.getByRole('button', { name: '新建模型' }).click()
    await page.getByPlaceholder('例如 glm-5.2', { exact: true }).fill(modelCode)
    await page.getByPlaceholder('例如 GLM-5.2', { exact: true }).fill(modelName)
    await page.getByRole('button', { name: '保存' }).click()
    await expect(page.locator('.n-modal').last()).toBeHidden()
    await expect(page.getByText(modelName)).toBeVisible()

    await page.locator('.n-data-table-tr', { hasText: modelName }).getByRole('button', { name: '价格' }).evaluate((node: HTMLElement) => node.click())
    const drawer = page.locator('.n-drawer').last()
    await drawer.locator('input').nth(0).fill('0.00000001')
    await drawer.locator('input').nth(1).fill('0.00000002')
    await drawer.getByRole('button', { name: '发布新价格' }).click()
    await expect(drawer).toContainText('0.00000001')

    await page.goto('/console/tenant-models')
    await expect(page.getByRole('heading', { name: '租户模型' })).toBeVisible()
    await page.locator('.n-data-table-tr', { hasText: modelName }).getByRole('button', { name: '发布为草稿' }).click()
    await expect(page.getByText('DRAFT')).toBeVisible()
  })

  for (const viewport of [{ width: 1440, height: 900 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
    test(`模型页面在 ${viewport.width}x${viewport.height} 无横向溢出`, async ({ browser }) => {
      const page = await browser.newPage({ viewport })
      await loginAndInitialize(page)
      for (const path of ['/console/platform-models', '/console/tenant-models', '/models']) {
        await page.goto(path)
        await expect(page.locator('body')).toBeVisible()
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
      }
      await page.close()
    })
  }
})
