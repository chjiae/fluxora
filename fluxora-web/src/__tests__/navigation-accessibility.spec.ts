import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import DocsView from '@/views/DocsView.vue'
import HomeView from '@/views/HomeView.vue'

function mountView(component: typeof HomeView | typeof DocsView) {
  return mount(component, {
    global: {
      plugins: [createPinia()],
      stubs: { RouterLink: true },
    },
  })
}

describe('移动端导航可访问性', () => {
  it.each([
    ['首页', HomeView],
    ['文档页', DocsView],
  ])('%s 的菜单按钮公开导航状态和关联关系', async (_, component) => {
    const wrapper = mountView(component)
    const menuButton = wrapper.find('.mobile')
    const navigation = wrapper.find('nav')

    expect(menuButton.attributes('aria-label')).toBe('打开导航菜单')
    expect(menuButton.attributes('aria-expanded')).toBe('false')
    expect(menuButton.attributes('aria-controls')).toBe(navigation.attributes('id'))

    await menuButton.trigger('click')

    expect(menuButton.attributes('aria-label')).toBe('关闭导航菜单')
    expect(menuButton.attributes('aria-expanded')).toBe('true')
  })
})
