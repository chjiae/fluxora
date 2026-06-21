<script setup lang="ts">
import { LayoutDashboard, Building2, Menu, Moon, Sun, UserRound, LogOut } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const themeStore = useThemeStore()
const collapsed = ref(false)

const menuItems = computed(() => {
  const items: { label: string; icon: any; to: string }[] = []
  items.push({ label: '概览', icon: LayoutDashboard, to: '/console/overview' })
  if (auth.canReadTenants) {
    items.push({ label: '租户管理', icon: Building2, to: '/console/tenants' })
  }
  return items
})

function isActive(to: string) {
  return route.path === to
}

function getCurrentLabel() {
  const item = menuItems.value.find(m => isActive(m.to))
  return item?.label || '概览'
}

async function handleLogout() {
  await auth.logoutAction()
  router.replace('/login')
}
</script>

<template>
  <div class="console" :class="{ collapsed }">
    <aside>
      <RouterLink class="brand" to="/">fluxora<span>.</span></RouterLink>
      <nav>
        <RouterLink
          v-for="item in menuItems" :key="item.to" :to="item.to"
          :class="{ active: isActive(item.to) }"
        >
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <button class="collapse" @click="collapsed = !collapsed">
        <Menu :size="18" /><span>收起菜单</span>
      </button>
    </aside>

    <section class="console-main">
      <header>
        <button class="mobile"><Menu :size="18" /></button>
        <div class="crumb">控制台 <b>/</b> {{ getCurrentLabel() }}</div>
        <div class="top-actions">
          <button
            class="theme-toggle"
            :aria-label="themeStore.theme === 'dark' ? '切换亮色' : '切换暗色'"
            @click="themeStore.toggle()"
          >
            <Sun v-if="themeStore.theme === 'dark'" :size="16" />
            <Moon v-else :size="16" />
          </button>
          <span class="user-name">
            <UserRound :size="16" />
            {{ auth.user?.displayName || auth.user?.username || '用户' }}
          </span>
          <button class="logout-btn" title="退出登录" @click="handleLogout">
            <LogOut :size="15" /> 退出
          </button>
        </div>
      </header>

      <main>
        <RouterView v-slot="{ Component: childComponent }">
          <component :is="childComponent" v-if="childComponent" />
        </RouterView>
        <div v-if="!route.matched.length || route.path === '/console' || route.path === '/console/'">
          <h1 style="font-size:1.5rem;margin-bottom:8px">你好，{{ auth.user?.displayName || '用户' }}。</h1>
          <p class="muted">{{ auth.isPlatformAdmin ? '平台管理员 — 可管理租户与平台配置' : '欢迎使用 Fluxora 控制台' }}</p>
          <div class="metrics">
            <article><small>当前角色</small><strong>{{ auth.isPlatformAdmin ? '平台管理员' : auth.isTenantAdmin ? '租户管理员' : '租户成员' }}</strong><span>控制台访问权限</span></article>
            <article><small>登录账号</small><strong>{{ auth.user?.username }}</strong><span>用户名</span></article>
            <article><small>租户状态</small><strong>{{ auth.user?.tenantId ? '已关联租户' : '平台级用户' }}</strong><span>当前归属</span></article>
          </div>
        </div>
      </main>
    </section>
  </div>
</template>
