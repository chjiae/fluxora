<script setup lang="ts">
import { ref } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { useRouter } from 'vue-router'
import { Eye, EyeOff } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import ThemeToggle from '@/components/ThemeToggle.vue'

const router = useRouter(); const auth = useAuthStore(); const message = useMessage(); const formRef = ref<FormInst | null>(null); const submitting = ref(false); const showPassword = ref(false)
const form = ref({ username: '', password: '' })
const rules: FormRules = { username: [{ required: true, message: '请输入用户名', trigger: ['input', 'blur'] }], password: [{ required: true, message: '请输入密码', trigger: ['input', 'blur'] }] }
async function handleLogin() { try { await formRef.value?.validate() } catch { return }; submitting.value = true; try { await auth.loginAction(form.value.username, form.value.password); if (auth.isPlatformAdmin) { await auth.checkSelfOperatedStatus(); await router.replace(auth.selfOperatedInitialized ? '/console/overview' : '/console/setup') } else await router.replace('/console/overview') } catch { message.error('用户名或密码错误，请重新输入') } finally { submitting.value = false } }
</script>
<template><main class="auth-page"><div class="auth-top"><RouterLink to="/" class="brand">fluxora<span>.</span></RouterLink><ThemeToggle /></div><n-card class="auth-card" :bordered="false"><h1>登录控制台</h1><p>使用您的账号进入 Fluxora 管理控制台。</p><n-form ref="formRef" :model="form" :rules="rules" label-placement="top" @submit.prevent="handleLogin"><n-form-item label="用户名" path="username"><n-input v-model:value="form.username" aria-label="用户名" autocomplete="username" placeholder="请输入用户名" :disabled="submitting" /></n-form-item><n-form-item label="密码" path="password"><n-input v-model:value="form.password" aria-label="密码" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" :disabled="submitting" @keyup.enter="handleLogin"><template #suffix><n-tooltip><template #trigger><n-button text :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword=!showPassword"><EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" /></n-button></template>{{ showPassword ? '隐藏密码' : '显示密码' }}</n-tooltip></template></n-input></n-form-item><n-button type="primary" block attr-type="submit" :loading="submitting">{{ submitting ? '登录中…' : '登录' }}</n-button></n-form></n-card></main></template>
<style scoped>
/* 登录页采用居中布局；亮色下 --bg 与卡片均为纯白，需通过边框+阴影让登录框浮起，避免与背景融为一体 */
.auth-page{min-height:100dvh;padding:24px;display:flex;align-items:center;justify-content:center;background:var(--bg)}
.auth-top{position:fixed;top:20px;left:24px;right:24px;display:flex;align-items:center;justify-content:space-between}
.brand{font-size:20px;font-weight:750;letter-spacing:-1px}
/* 登录卡片：亮色阴影柔和、暗色阴影更深，避免和背景同色无法识别边界 */
.auth-card{width:min(100%,420px);padding:12px;border:1px solid var(--border);border-radius:12px;box-shadow:0 8px 24px rgba(15,23,42,.08),0 2px 6px rgba(15,23,42,.04)}
[data-theme="dark"] .auth-card{box-shadow:0 12px 32px rgba(0,0,0,.45),0 2px 8px rgba(0,0,0,.35)}
.auth-card h1{margin:0;font-size:1.6rem}
.auth-card p{margin:8px 0 28px;color:var(--text-muted)}
</style>
