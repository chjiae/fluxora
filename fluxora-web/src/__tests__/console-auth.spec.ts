import { describe, expect, it } from 'vitest'

describe('console and authentication views', () => {
  it('uses form validation for login and initialization confirmation password', async () => {
    const login = await import('@/views/LoginView.vue')
    const setup = await import('@/views/SelfOperatedSetupView.vue')
    expect(String(login.default.setup)).toContain('formRef')
    expect(String(setup.default.setup)).toContain('confirmPassword')
  })

  it('renders console through a shell with one scroll content region', async () => {
    // 直接读取 SFC 源文件，断言主内容区使用 console-content 单一滚动容器；
    // 编译后的 render 函数会把静态 class 提升到 _hoisted 常量，不能直接 toContain。
    const fs = await import('node:fs/promises')
    const path = await import('node:path')
    const source = await fs.readFile(
      path.resolve(__dirname, '../components/ConsoleShell.vue'),
      'utf8',
    )
    expect(source).toContain('console-content')
  })
})
