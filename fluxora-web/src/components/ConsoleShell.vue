<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Building2, LayoutDashboard, Menu, UserRound, Users } from 'lucide-vue-next'
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
])
const title = computed(() => {
  // 嵌套的「指定租户成员管理」路径在菜单中无对应条目，单独命名以保留面包屑可读性
  if (/^\/console\/tenants\/[^/]+\/members$/.test(route.path)) return '成员管理'
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
