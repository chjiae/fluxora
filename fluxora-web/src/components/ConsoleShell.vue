<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Building2, CreditCard, KeyRound, LayoutDashboard, Menu, Network, Plug, Sparkles, UserRound, Users, Wallet, Boxes, ChartNoAxesCombined, Scale } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import ThemeToggle from '@/components/ThemeToggle.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mobileOpen = ref(false)

const menuOptions = computed(() => [
  { label: '概览', key: '/console/overview', icon: () => h(LayoutDashboard, { size: 18 }) },
  ...(auth.canReadTenants ? [{ label: '租户管理', key: '/console/tenants', icon: () => h(Building2, { size: 18 }) }] : []),
  // 仅租户管理员（无平台租户管理权限）显示独立的「成员管理」入口；
  // 平台管理员通过租户列表的「管理成员」按钮进入嵌套路径。
  ...(auth.canReadMembers && !auth.canReadTenants
    ? [{ label: '成员管理', key: '/console/members', icon: () => h(Users, { size: 18 }) }]
    : []),
  // 我的 API Key：只要拥有 API_KEY_SELF_MANAGE 权限（含平台管理员被分配该权限的情况）就显示
  ...(auth.canManageOwnApiKeys
    ? [{ label: '我的 API Key', key: '/console/api-keys', icon: () => h(KeyRound, { size: 18 }) }]
    : []),
  // 我的额度：拥有 CREDIT_SELF_READ 权限的租户用户
  ...(auth.canReadOwnCredit
    ? [{ label: '我的额度', key: '/console/credit', icon: () => h(Wallet, { size: 18 }) }]
    : []),
  // 额度管理：租户管理员或平台管理员
  ...(auth.canAdjustTenantCredit || auth.canAdjustCrossTenantCredit
    ? [{ label: '额度管理', key: '/console/credit/manage', icon: () => h(Wallet, { size: 18 }) }]
    : []),
  // 卡密充值：拥有 CARD_SELF_REDEEM 权限的租户用户
  ...(auth.canRedeemCards
    ? [{ label: '卡密充值', key: '/console/cards/redeem', icon: () => h(CreditCard, { size: 18 }) }]
    : []),
  // 卡密管理：租户管理员或平台管理员
  ...(auth.canManageCards || auth.canManageCrossTenantCards
    ? [{ label: '卡密管理', key: '/console/cards/manage', icon: () => h(CreditCard, { size: 18 }) }]
    : []),
  // 上游配置：平台管理员与租户管理员可见；普通成员无 UPSTREAM_READ 权限不显示
  ...(auth.canReadUpstream
    ? [
        { label: '上游厂商', key: '/console/providers', icon: () => h(Network, { size: 18 }) },
        { label: '接入地址', key: '/console/provider-base-urls', icon: () => h(Plug, { size: 18 }) },
        { label: '上游通道', key: '/console/provider-channels', icon: () => h(Network, { size: 18 }) },
      ]
    : []),
  // 模型目录：所有拥有 TENANT_MODEL_PUBLIC_READ 的用户可见（含租户成员）
  // 与下方管理页分离：目录是 C 端浏览视图，不暴露路由/映射/价格版本等内部细节
  ...(auth.canReadPublicModelCatalog
    ? [{ label: '模型目录', key: '/console/models', icon: () => h(Sparkles, { size: 18 }) }]
    : []),
  ...(auth.isLoggedIn ? [{ label: '请求日志', key: '/console/request-logs', icon: () => h(ChartNoAxesCombined, { size: 18 }) }] : []),
  ...(auth.canAdjustCrossTenantCredit
    ? [{ label: '余额对账', key: '/console/billing/reconciliations', icon: () => h(Scale, { size: 18 }) }]
    : []),
  // 租户模型管理：仅 TENANT_MODEL_READ 权限可见（PLATFORM_ADMIN / TENANT_ADMIN）
  ...(auth.canReadTenantModels
    ? [{ label: '租户模型', key: '/console/tenant-models', icon: () => h(Boxes, { size: 18 }) }]
    : []),
])
const title = computed(() => {
  // 嵌套的「指定租户成员管理」路径在菜单中无对应条目，单独命名以保留面包屑可读性
  if (/^\/console\/tenants\/[^/]+\/members$/.test(route.path)) return '成员管理'
  if (/^\/console\/tenants\/[^/]+\/api-keys$/.test(route.path)) return 'API Key'
  if (/^\/console\/tenants\/[^/]+\/credit\/manage$/.test(route.path)) return '额度管理'
  if (route.path === '/console/credit/manage') return '额度管理'
  if (route.path === '/console/cards/redeem') return '卡密充值'
  if (route.path === '/console/cards/manage') return '卡密管理'
  if (/^\/console\/tenants\/[^/]+\/cards\/manage$/.test(route.path)) return '卡密管理'
  if (route.path === '/console/providers') return '上游厂商'
  if (route.path === '/console/provider-base-urls') return '接入地址'
  if (route.path === '/console/provider-channels') return '上游通道'
  if (route.path === '/console/tenant-models') return '租户模型'
  if (route.path === '/console/models') return '模型目录'
  if (route.path === '/console/request-logs') return '请求日志'
  if (route.path === '/console/billing/reconciliations') return '余额对账'
  return menuOptions.value.find(item => item.key === route.path)?.label || '概览'
})

async function go(value: string) {
  mobileOpen.value = false
  await router.push(value)
}
async function logout() {
  await auth.logoutAction()
  await router.replace('/login')
}
</script>

<template>
  <div class="console-shell">
    <!-- 桌面端侧边栏：内部独立滚动 -->
    <aside class="desktop-sider">
      <RouterLink to="/" class="brand">fluxora<span>.</span></RouterLink>
      <n-menu :value="route.path" :options="menuOptions" @update:value="go" />
    </aside>

    <div class="console-main">
      <header class="console-header">
        <div class="context">
          <n-button
            class="mobile-menu"
            quaternary
            circle
            aria-label="打开控制台菜单"
            @click="mobileOpen = true"
          ><Menu :size="20" /></n-button>
          <span>控制台 / <strong>{{ title }}</strong></span>
        </div>
        <div class="header-actions">
          <ThemeToggle />
          <n-dropdown
            :options="[{ label: '退出登录', key: 'logout' }]"
            @select="logout"
          >
            <n-button quaternary>
              <UserRound :size="16" />
              {{ auth.user?.displayName || auth.user?.username || '用户' }} · 退出
            </n-button>
          </n-dropdown>
        </div>
      </header>

      <!--
        内容区使用原生 <main> + 原生 overflow，避免 Naive 的 n-scrollbar 包装层
        破坏子页面（如租户管理）的 height:100% 链路，从而保证 flex-height 表格
        能正确撑开。
      -->
      <main
        id="console-content"
        data-testid="console-content"
        class="console-content"
      >
        <slot />
      </main>
    </div>

    <!-- 移动端：抽屉里展示菜单 -->
    <n-drawer v-model:show="mobileOpen" placement="left" :width="264">
      <n-drawer-content title="fluxora">
        <n-menu :value="route.path" :options="menuOptions" @update:value="go" />
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped>
.console-shell {
  height: 100dvh;
  display: flex;
  background: var(--bg);
  color: var(--text);
}

.desktop-sider {
  width: 232px;
  flex-shrink: 0;
  height: 100dvh;
  border-right: 1px solid var(--border);
  background: var(--surface);
  overflow-y: auto;
}
.brand {
  display: block;
  padding: 20px 18px;
  font-size: 19px;
  font-weight: 750;
  letter-spacing: -1px;
}

.console-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: 100dvh;
}

.console-header {
  height: 58px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.context,
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/*
  关键：main 既是滚动容器又用 flex 列布局。
  - flex:1 让它占满 console-main 剩余空间
  - min-height:0 解锁 flex 子项的内部滚动
  - 默认子页面（普通文档型）走 overflow:auto 整页滚动
  - 子页面如果自己声明 height:100% + 内部分区 grid（如租户管理），
    会自动接管布局，让表格区独立滚动而顶部/底部常驻。
*/
.console-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 28px 32px;
  display: flex;
  flex-direction: column;
}
.console-content > :deep(*) {
  flex: 1 0 auto;
}

.mobile-menu {
  display: none;
}

@media (max-width: 720px) {
  .desktop-sider {
    display: none;
  }
  .mobile-menu {
    display: inline-flex;
  }
  .console-header {
    padding: 0 12px;
  }
  .console-content {
    padding: 20px 16px;
  }
  .header-actions :deep(.n-button) {
    font-size: 12px;
  }
}
</style>
