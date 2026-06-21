<script setup lang="ts">import { useAuthStore } from '@/stores/auth'; const auth = useAuthStore()</script>
<template><section><h1>你好，{{ auth.user?.displayName || '用户' }}。</h1><p class="muted">这里展示与你的账号和权限相关的控制台入口。</p><n-card title="当前访问范围" size="small"><p>角色：{{ auth.isPlatformAdmin ? '平台管理员' : auth.isTenantAdmin ? '租户管理员' : '租户成员' }}</p><p>待办：{{ auth.canReadTenants ? '可进入租户管理查看和维护授权范围内的租户。' : '当前没有待处理的管理事项。' }}</p></n-card></section></template>
<style scoped>h1{margin:0 0 8px;font-size:1.5rem}.muted{margin:0 0 24px;color:var(--text-muted)}.n-card p{margin:8px 0}</style>
