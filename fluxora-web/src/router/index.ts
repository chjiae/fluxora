import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import DocsView from '../views/DocsView.vue'
import ConsoleView from '../views/ConsoleView.vue'
import LoginView from '../views/LoginView.vue'
import SelfOperatedSetupView from '../views/SelfOperatedSetupView.vue'
import TenantManagementView from '../views/TenantManagementView.vue'
import { useAuthStore } from '@/stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/docs', component: DocsView },
    { path: '/login', component: LoginView, meta: { guest: true } },
    { path: '/console/setup', component: SelfOperatedSetupView, meta: { requiresAuth: true } },
    {
      path: '/console/:section?',
      component: ConsoleView,
      meta: { requiresAuth: true },
      children: [
        { path: 'tenants', component: TenantManagementView },
        { path: 'overview', component: { template: '<div></div>' } },
      ],
    },
  ],
})

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  // 首次进入时检查登录状态
  if (!auth.user && !auth.loading) {
    await auth.checkAuth()
  }

  if (to.meta.guest) {
    // 已登录用户访问登录页，重定向到控制台
    if (auth.isLoggedIn) {
      return next('/console/overview')
    }
    return next()
  }

  if (to.meta.requiresAuth) {
    if (!auth.isLoggedIn) {
      return next('/login')
    }

    // 平台管理员且自营未初始化时，跳转初始化向导（setup页面除外）
    if (auth.isPlatformAdmin && to.path !== '/console/setup') {
      await auth.checkSelfOperatedStatus()
      if (!auth.selfOperatedInitialized) {
        return next('/console/setup')
      }
    }
  }

  next()
})
