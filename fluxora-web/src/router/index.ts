import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import DocsView from '../views/DocsView.vue'
import ConsoleView from '../views/ConsoleView.vue'
import LoginView from '../views/LoginView.vue'
import SelfOperatedSetupView from '../views/SelfOperatedSetupView.vue'
import TenantManagementView from '../views/TenantManagementView.vue'
import MemberManagementView from '../views/MemberManagementView.vue'
import MyApiKeysView from '../views/MyApiKeysView.vue'
import MyCreditView from '../views/MyCreditView.vue'
import CreditManagementView from '../views/CreditManagementView.vue'
import CardRedeemView from '../views/CardRedeemView.vue'
import CardBatchManagementView from '../views/CardBatchManagementView.vue'
import ConsoleOverviewView from '../views/ConsoleOverviewView.vue'
import ProviderManagementView from '../views/ProviderManagementView.vue'
import ProviderBaseUrlManagementView from '../views/ProviderBaseUrlManagementView.vue'
import ProviderChannelManagementView from '../views/ProviderChannelManagementView.vue'
import TenantModelManagementView from '../views/TenantModelManagementView.vue'
import PublicModelCatalogView from '../views/PublicModelCatalogView.vue'
import { useAuthStore } from '@/stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/docs', component: DocsView },
    { path: '/models', component: PublicModelCatalogView, meta: { requiresAuth: true } },
    { path: '/login', component: LoginView, meta: { guest: true } },
    { path: '/console/setup', component: SelfOperatedSetupView, meta: { requiresAuth: true } },
    {
      path: '/console',
      component: ConsoleView,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/console/overview' },
        { path: 'tenants', component: TenantManagementView },
        {
          // 平台管理员视角：在具体租户上下文里管理成员
          path: 'tenants/:tenantId/members',
          component: MemberManagementView,
          props: route => ({ tenantId: Number(route.params.tenantId) }),
        },
        {
          // 租户管理员视角：管理自身租户内的成员
          path: 'members',
          component: MemberManagementView,
        },
        {
          // 我的 API Key
          path: 'api-keys',
          component: MyApiKeysView,
        },
        {
          // API Key 管理（租户管理员视角路径；如果是嵌套=/console/tenants/:id/api-keys 还未实现使用顶层独立路径）
          path: 'api-keys/manage',
          component: MyApiKeysView,
          props: { isAdminView: true },
        },
        {
          // 我的额度
          path: 'credit',
          component: MyCreditView,
        },
        {
          // 额度管理
          path: 'credit/manage',
          component: CreditManagementView,
        },
        {
          // 卡密充值（普通用户 / 租户管理员均可）
          path: 'cards/redeem',
          component: CardRedeemView,
        },
        {
          // 卡密批次管理（租户管理员路径）
          path: 'cards/manage',
          component: CardBatchManagementView,
        },
        {
          // 平台管理员嵌套：指定租户的卡密管理
          path: 'tenants/:tenantId/cards/manage',
          component: CardBatchManagementView,
          props: route => ({ tenantId: Number(route.params.tenantId) }),
        },
        { path: 'overview', component: ConsoleOverviewView },
        { path: 'providers', component: ProviderManagementView },
        { path: 'provider-base-urls', component: ProviderBaseUrlManagementView },
        { path: 'provider-channels', component: ProviderChannelManagementView },
        { path: 'tenant-models', component: TenantModelManagementView },
        { path: 'models', component: PublicModelCatalogView },
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

    // 成员管理页面：嵌套租户路径与租户管理员入口均需要 MEMBER_READ
    if ((to.path === '/console/members' || /^\/console\/tenants\/[^/]+\/members$/.test(to.path))
        && !auth.canReadMembers) {
      return next('/console/overview')
    }

    // API Key 管理：自身入口需要 API_KEY_SELF_MANAGE
    if (to.path === '/console/api-keys' && !auth.canManageOwnApiKeys) {
      return next('/console/overview')
    }
    // 额度页面权限
    if (to.path === '/console/credit' && !auth.canReadOwnCredit) {
      return next('/console/overview')
    }
    if (to.path === '/console/credit/manage'
        && !(auth.canAdjustTenantCredit || auth.canAdjustCrossTenantCredit)) {
      return next('/console/overview')
    }
    // 卡密充值：CARD_SELF_REDEEM
    if (to.path === '/console/cards/redeem' && !auth.canRedeemCards) {
      return next('/console/overview')
    }
    // 卡密管理：本租户 / 跨租户
    if ((to.path === '/console/cards/manage'
          || /^\/console\/tenants\/[^/]+\/cards\/manage$/.test(to.path))
        && !(auth.canManageCards || auth.canManageCrossTenantCards)) {
      return next('/console/overview')
    }

    // 自营初始化仅平台管理员可访问，租户管理员重定向到概览
    if (to.path === '/console/setup' && !auth.isPlatformAdmin) {
      return next('/console/overview')
    }

    // 上游配置页面需要 UPSTREAM_READ 权限，普通成员重定向到概览
    if ((to.path === '/console/providers' || to.path === '/console/provider-base-urls' || to.path === '/console/provider-channels')
        && !auth.canReadUpstream) {
      return next('/console/overview')
    }
    // 租户模型管理页：只允许有 TENANT_MODEL_READ 的角色（PLATFORM_ADMIN / TENANT_ADMIN）进入；
    // 普通成员只能访问公开目录 /models（依赖 TENANT_MODEL_PUBLIC_READ）
    if (to.path === '/console/tenant-models' && !auth.canReadTenantModels) {
      return next('/console/overview')
    }

    // 自营初始化仅平台管理员可访问，租户管理员重定向到概览
    if (auth.isPlatformAdmin && to.path !== '/console/setup') {
      await auth.checkSelfOperatedStatus()
      if (!auth.selfOperatedInitialized) {
        return next('/console/setup')
      }
    }
  }

  next()
})
