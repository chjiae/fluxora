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

    // 租户管理页面需要 TENANT_READ 权限，无权限用户重定向到概览
    if (to.path === '/console/tenants' && !auth.canReadTenants) {
      return next('/console/overview')
    }

    // 自营初始化仅平台管理员可访问，租户管理员重定向到概览
    if (to.path === '/console/setup' && !auth.isPlatformAdmin) {
      return next('/console/overview')
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
