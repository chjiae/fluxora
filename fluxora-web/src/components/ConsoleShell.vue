<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Building2, LayoutDashboard, LogOut, Menu, UserRound } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import ThemeToggle from '@/components/ThemeToggle.vue'

const auth = useAuthStore(); const route = useRoute(); const router = useRouter(); const mobileOpen = ref(false)
const menuOptions = computed(() => [{ label: '概览', key: '/console/overview', icon: () => h(LayoutDashboard, { size: 18 }) }, ...(auth.canReadTenants ? [{ label: '租户管理', key: '/console/tenants', icon: () => h(Building2, { size: 18 }) }] : [])])
const title = computed(() => menuOptions.value.find(item => item.key === route.path)?.label || '概览')
async function go(value: string) { mobileOpen.value = false; await router.push(value) }
async function logout() { await auth.logoutAction(); await router.replace('/login') }
</script>
<template>
  <n-layout has-sider class="console-shell">
    <n-layout-sider class="desktop-sider" bordered :native-scrollbar="false" :width="232"><RouterLink to="/" class="brand">fluxora<span>.</span></RouterLink><n-menu :value="route.path" :options="menuOptions" @update:value="go" /></n-layout-sider>
    <n-layout class="console-main"><n-layout-header bordered class="console-header"><div class="context"><n-button class="mobile-menu" quaternary circle aria-label="打开控制台菜单" @click="mobileOpen=true"><Menu :size="20" /></n-button><span>控制台 / <strong>{{ title }}</strong></span></div><div class="header-actions"><ThemeToggle /><n-dropdown :options="[{ label: '退出登录', key: 'logout' }]" @select="logout"><n-button quaternary><UserRound :size="16" />{{ auth.user?.displayName || auth.user?.username || '用户' }} · 退出</n-button></n-dropdown></div></n-layout-header><n-layout-content id="console-content" data-testid="console-content" class="console-content" :native-scrollbar="false"><slot /></n-layout-content></n-layout>
    <n-drawer v-model:show="mobileOpen" placement="left" :width="264"><n-drawer-content title="fluxora"><n-menu :value="route.path" :options="menuOptions" @update:value="go" /></n-drawer-content></n-drawer>
  </n-layout>
</template>
<style scoped>
.console-shell{height:100dvh}.desktop-sider{height:100dvh}.brand{display:block;padding:20px 18px;font-size:19px;font-weight:750;letter-spacing:-1px}.console-main{height:100dvh;min-width:0}.console-header{height:58px;display:flex;align-items:center;justify-content:space-between;padding:0 24px}.context,.header-actions{display:flex;align-items:center;gap:10px}.console-content{height:calc(100dvh - 58px);padding:28px 32px;overflow:auto}.mobile-menu{display:none}@media(max-width:720px){.desktop-sider{display:none}.mobile-menu{display:inline-flex}.console-header{padding:0 12px}.console-content{padding:20px 16px}.header-actions :deep(.n-button){font-size:12px}}
</style>
