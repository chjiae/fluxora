import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  // E2E 共享同一个真实控制面数据库；串行避免跨文件并发创建/停用同一默认租户造成数据竞争。
  workers: 1,
  use: { baseURL: 'http://localhost:5173' },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 900 } } },
    { name: 'tablet', use: { viewport: { width: 768, height: 1024 } } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 } } },
  ],
})
