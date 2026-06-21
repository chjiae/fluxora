import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AsyncState from '@/components/AsyncState.vue'
import StatusTag from '@/components/StatusTag.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useThemeStore } from '@/stores/theme'

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('提供可访问的主题切换名称并切换主题', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useThemeStore()
    store.setTheme('dark')
    const wrapper = mount(ThemeToggle, { global: { plugins: [pinia] } })

    expect(wrapper.get('button').attributes('aria-label')).toBe('切换为亮色主题')
    await wrapper.get('button').trigger('click')

    expect(store.theme).toBe('light')
  })
})

describe('主题偏好', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('首次跟随系统主题，手动选择后保持用户偏好', () => {
    localStorage.clear()
    const listeners = new Set<(event: MediaQueryListEvent) => void>()
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: false,
      addEventListener: (_event: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
      removeEventListener: (_event: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener),
    })))
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useThemeStore()

    listeners.forEach(listener => listener({ matches: true } as MediaQueryListEvent))
    expect(store.theme).toBe('dark')

    store.setTheme('light')
    listeners.forEach(listener => listener({ matches: true } as MediaQueryListEvent))
    expect(store.theme).toBe('light')
    store.$dispose()
  })
})

describe('StatusTag', () => {
  it.each([
    ['tenant', 'SELF_OPERATED', '自营'],
    ['tenant', 'STANDARD', '标准'],
    ['status', 'ENABLED', '已启用'],
    ['status', 'DISABLED', '已停用'],
    ['status', 'EXPIRED', '已过期'],
    ['status', 'DELETED', '已删除'],
  ] as const)('将 %s 的 %s 映射为 %s', (category, value, expected) => {
    const wrapper = mount(StatusTag, { props: { category, value } })

    expect(wrapper.text()).toContain(expected)
  })
})

describe('AsyncState', () => {
  it('将受限安全文案键映射为固定中文提示', () => {
    const wrapper = mount(AsyncState, {
      props: { state: 'error', errorKey: 'network' },
    })

    expect(wrapper.text()).toContain('网络连接失败，请检查网络后重试')
  })

  it('错误状态仅显示安全提示并允许重试', async () => {
    const wrapper = mount(AsyncState, {
      props: {
        state: 'error',
        description: 'Error: SQLSTATE 500 at /internal/users',
      },
    })

    expect(wrapper.text()).toContain('服务暂时不可用，请稍后重试')
    expect(wrapper.text()).not.toContain('SQLSTATE')
    expect(wrapper.text()).not.toContain('/internal/users')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('不会透传未受限的技术文本', () => {
    const wrapper = mount(AsyncState, {
      props: {
        state: 'error',
        description: 'com.zaxxer.hikari.HikariDataSource',
      },
    })

    expect(wrapper.text()).toContain('服务暂时不可用，请稍后重试')
    expect(wrapper.html()).not.toContain('com.zaxxer.hikari.HikariDataSource')
  })
})
