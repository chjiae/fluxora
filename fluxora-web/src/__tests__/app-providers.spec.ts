import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import {
  dateZhCN,
  NConfigProvider,
  NDialogProvider,
  NLoadingBarProvider,
  NMessageProvider,
  NNotificationProvider,
  zhCN,
} from 'naive-ui'
import { describe, expect, it } from 'vitest'
import App from '@/App.vue'

describe('App 全局提供器', () => {
  function mountApp() {
    const pinia = createPinia()
    setActivePinia(pinia)

    return mount(App, {
      global: {
        plugins: [pinia],
        stubs: {
          RouterView: true,
        },
      },
    })
  }

  it('为 Naive UI 配置中文语言与日期语言', () => {
    const wrapper = mountApp()

    const configProvider = wrapper.findComponent(NConfigProvider)
    expect(configProvider.props('locale')).toBe(zhCN)
    expect(configProvider.props('dateLocale')).toBe(dateZhCN)
  })

  it('提供全局反馈能力', () => {
    const wrapper = mountApp()

    expect(wrapper.findComponent(NLoadingBarProvider).exists()).toBe(true)
    expect(wrapper.findComponent(NNotificationProvider).exists()).toBe(true)
    expect(wrapper.findComponent(NMessageProvider).exists()).toBe(true)
    expect(wrapper.findComponent(NDialogProvider).exists()).toBe(true)
  })
})
