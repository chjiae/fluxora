<script setup lang="ts">
import { LayoutDashboard, Building2, Sun, Moon, UserRound, LogOut } from 'lucide-vue-next'
import { computed, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const themeStore = useThemeStore()

const menuOptions = computed(() => {
  const items: any[] = [{ label: '概览', key: '/console/overview', icon: () => h(LayoutDashboard, { size: 18 }) }]
  if (auth.canReadTenants) items.push({ label: '租户管理', key: '/console/tenants', icon: () => h(Building2, { size: 18 }) })
  return items
})

const currentLabel = computed(() => (menuOptions.value.find((m: any) => m.key === route.path)?.label as string) || '概览')

async function handleLogout() { await auth.logoutAction(); router.replace('/login') }
</script>

<template>
  <n-layout class="console" has-sider>
    <n-layout-sider bordered :width="240" collapse-mode="width" :collapsed-width="64" :native-scrollbar="false">
      <div class="sidebar-brand"><RouterLink to="/" class="brand">fluxora<span>.</span></RouterLink></div>
      <n-menu :options="menuOptions" :value="route.path" @update:value="(v: string) => router.push(v)" />
    </n-layout-sider>
    <n-layout>
      <n-layout-header class="console-hdr" bordered>
        <span class="crumb">控制台 <b>/</b> {{ currentLabel }}</span>
        <div class="hdr-actions">
          <n-button size="small" quaternary @click="themeStore.toggle()"><template #icon><Sun v-if="themeStore.theme==='dark'" :size="16"/><Moon v-else :size="16"/></template></n-button>
          <span class="user-name"><UserRound :size="16"/>{{ auth.user?.displayName || auth.user?.username || '用户' }}</span>
          <n-dropdown :options="[{ label: '退出登录', key: 'logout' }]" @select="handleLogout">
            <n-button size="small" quaternary><LogOut :size="15"/> 退出</n-button>
          </n-dropdown>
        </div>
      </n-layout-header>
      <n-layout-content class="console-ct" :native-scrollbar="false">
        <RouterView v-slot="{ Component: c }"><component :is="c" v-if="c"/></RouterView>
        <div v-if="!route.matched.length||route.path==='/console'||route.path==='/console/'||route.path==='/console/overview'">
          <h1 style="font-size:1.5rem;margin-bottom:8px">你好，{{ auth.user?.displayName || '用户' }}。</h1>
          <p class="muted">{{ auth.isPlatformAdmin?'平台管理员':'欢迎' }}</p>
          <div class="metrics">
            <n-card size="small" title="当前角色">{{auth.isPlatformAdmin?'平台管理员':auth.isTenantAdmin?'租户管理员':'租户成员'}}</n-card>
            <n-card size="small" title="登录账号">{{auth.user?.username}}</n-card>
            <n-card size="small" title="租户状态">{{auth.user?.tenantId?'已关联租户':'平台级用户'}}</n-card>
          </div>
        </div>
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<style scoped>
.console{height:100dvh}
.sidebar-brand{padding:20px 16px;font-size:18px}
.brand{font-weight:700;letter-spacing:-1.2px}
.console-hdr{display:flex;align-items:center;justify-content:space-between;padding:0 24px;height:56px}
.crumb{font-size:13px;color:var(--text-muted)}
.crumb b{color:var(--text);font-weight:600}
.hdr-actions{display:flex;align-items:center;gap:10px}
.user-name{display:flex;align-items:center;gap:6px;font-size:13px}
.console-ct{padding:28px 32px}
.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin-top:28px}
.muted{color:var(--text-muted);font-size:13px}
@media(max-width:720px){.console-ct{padding:20px 16px}}
</style>
