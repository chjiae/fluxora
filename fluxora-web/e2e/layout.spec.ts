import { expect, test } from '@playwright/test'

for (const path of ['/', '/docs', '/console']) {
  test(`${path} 在当前视口没有横向溢出`, async ({ page }) => {
    await page.goto(path)
    await expect(page.locator('body')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  })
}
