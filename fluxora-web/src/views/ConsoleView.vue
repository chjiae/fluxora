<script setup lang="ts">
import { LayoutDashboard, Building2, Menu, Moon, Sun, UserRound, LogOut } from 'lucide-vue-next'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const dark = ref(false)

const menuItems = computed(() => {
  const items: { label: string; icon: any; to: string }[] = []
  items.push({ label: '概览', icon: LayoutDashboard, to: '/console/overview' })
  if (auth.isPlatformAdmin) {
    items.push({ label: '租户管理', icon: Building2, to: '/console/tenants' })
  }
  return items
})

function getCurrentLabel() {
  const section = router.currentRoute.value.params.section
  if (section === 'tenants' || section === '租户管理') return '租户管理'
  return '概览'
}

async function handleLogout() {
  await auth.logoutAction()
  router.replace('/login')
}
</script>

<template>
  <div class="console" :class="{ collapsed, dark }">
    <aside>
      <RouterLink class="brand" to="/">fluxora<span>.</span></RouterLink>
      <nav>
        <RouterLink
          v-for="item in menuItems"
          :key="item.to"
          :to="item.to"
          :class="{ active: getCurrentLabel() === item.label }"
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
        <button class="mobile"><Menu /></button>
        <div class="crumb">控制台 <b>/</b> {{ getCurrentLabel() }}</div>
        <div class="top-actions">
          <button @click="dark = !dark">
            <Sun v-if="dark" :size="17" />
            <Moon v-else :size="17" />
          </button>
          <span class="user-name">
            <UserRound :size="17" />
            {{ auth.user?.displayName || auth.user?.username || '用户' }}
          </span>
          <button class="logout-btn" title="退出登录" @click="handleLogout">
            <LogOut :size="16" />
          </button>
        </div>
      </header>

      <main>
        <RouterView v-if="router.currentRoute.value.matched.length > 1" />
        <div v-else>
          <p class="eyebrow">OVERVIEW</p>
          <h1>你好，{{ auth.user?.displayName || '用户' }}。</h1>
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

<style scoped>
.logout-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: none;
  cursor: pointer;
  color: var(--muted);
  transition: color .15s;
}
.logout-btn:hover { color: #d92d20; }
.user-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text);
}
</style>
