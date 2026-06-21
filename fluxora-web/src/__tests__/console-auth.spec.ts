import { describe, expect, it } from 'vitest'

describe('console and authentication views', () => {
  it('uses form validation for login and initialization confirmation password', async () => {
    const login = await import('@/views/LoginView.vue')
    const setup = await import('@/views/SelfOperatedSetupView.vue')
    expect(String(login.default.setup)).toContain('formRef')
    expect(String(setup.default.setup)).toContain('confirmPassword')
  })

  it('renders console through a shell with one scroll content region', async () => {
    const shell = await import('@/components/ConsoleShell.vue')
    expect(String(shell.default.render)).toContain('console-content')
  })
})
