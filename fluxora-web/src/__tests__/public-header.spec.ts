import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'

import PublicHeader from '@/components/PublicHeader.vue'

describe('PublicHeader', () => {
  it('打开移动导航后展示全部入口和主题切换，并维护可访问状态', async () => {
    const wrapper = mount(PublicHeader, {
      attachTo: document.body,
      global: {
        plugins: [createPinia()],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    })
    const menuButton = wrapper.get('[data-testid="public-menu-toggle"]')

    expect(menuButton.attributes('aria-label')).toBe('打开导航菜单')
    expect(menuButton.attributes('aria-expanded')).toBe('false')
    expect(menuButton.attributes('aria-controls')).toBe('public-navigation')

    await menuButton.trigger('click')

    expect(menuButton.attributes('aria-label')).toBe('关闭导航菜单')
    expect(menuButton.attributes('aria-expanded')).toBe('true')
    const navigation = document.querySelector('#public-navigation')
    expect(navigation?.textContent).toContain('产品介绍')
    expect(navigation?.textContent).toContain('产品优势')
    expect(navigation?.textContent).toContain('文档')
    expect(navigation?.textContent).toContain('FAQ')
    expect(navigation?.textContent).toContain('进入控制台')
    expect(navigation?.querySelector('button[aria-label^="切换为"]')).not.toBeNull()
    wrapper.unmount()
  })
})
