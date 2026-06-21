import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'

import App from '@/App.vue'
import HomeView from '@/views/HomeView.vue'
import DocsView from '@/views/DocsView.vue'

describe('基础路由', () => {
  it.each([
    ['/', 'Fluxora'],
    ['/docs', '文档'],
  ])('在 %s 渲染 %s', async (path, expectedText) => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: HomeView },
        { path: '/docs', component: DocsView },
      ],
    })
    router.push(path)
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })

    expect(wrapper.text()).toContain(expectedText)
  })
})
