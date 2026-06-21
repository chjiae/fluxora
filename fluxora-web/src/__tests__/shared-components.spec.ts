import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

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
})
