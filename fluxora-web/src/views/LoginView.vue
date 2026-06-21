<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { Sun, Moon } from 'lucide-vue-next'

const router = useRouter()
const auth = useAuthStore()
const themeStore = useThemeStore()
const message = useMessage()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const isDev = import.meta.env.DEV

async function handleLogin() {
  if (!username.value || !password.value) { message.warning('请输入用户名和密码'); return }
  submitting.value = true
  try {
    await auth.loginAction(username.value, password.value)
    if (auth.isPlatformAdmin) { await auth.checkSelfOperatedStatus(); await router.replace(auth.selfOperatedInitialized ? '/console/overview' : '/console/setup') }
    else { await router.replace('/console/overview') }
  } catch (e: any) { message.error(e.userMessage || '用户名或密码错误，请重新输入') } finally { submitting.value = false }
}
</script>

<template>
  <div class="login-page">
    <div class="login-hero">
      <RouterLink to="/" class="brand">fluxora<span>.</span></RouterLink>
      <h2>API 中转平台</h2>
      <p class="subtitle">统一接入多上游、多协议与流式 API，为开发者提供稳定可靠的中转服务。</p>
      <ul class="features"><li>多租户管理与权限隔离</li><li>OpenAI / Anthropic 协议兼容</li><li>流式中继与用量治理</li></ul>
      <div class="hero-footer">
        <n-button size="small" quaternary @click="themeStore.toggle()"><template #icon><Sun v-if="themeStore.theme==='dark'" :size="16"/><Moon v-else :size="16"/></template></n-button>
        <RouterLink to="/docs" class="text-link">文档</RouterLink>
        <RouterLink to="/" class="text-link">官网</RouterLink>
      </div>
    </div>
    <div class="login-form-panel">
      <h3>登录控制台</h3>
      <n-form class="login-form" @submit.prevent="handleLogin">
        <n-form-item label="用户名" required><n-input v-model:value="username" placeholder="请输入用户名" :disabled="submitting" clearable /></n-form-item>
        <n-form-item label="密码" required>
          <n-input v-model:value="password" :type="showPassword?'text':'password'" placeholder="请输入密码" :disabled="submitting">
            <template #suffix><n-button text @click="showPassword=!showPassword" style="font-size:18px">{{showPassword?'👁':'👁‍🗨'}}</n-button></template>
          </n-input>
        </n-form-item>
        <n-button type="primary" block :loading="submitting" attr-type="submit" size="large">{{submitting?'登录中...':'登录'}}</n-button>
      </n-form>
      <div v-if="isDev" class="login-dev-hint">本地开发初始账号：<code>admin</code> / 密码见环境变量 <code>INIT_ADMIN_PASSWORD</code></div>
    </div>
  </div>
</template>

<style scoped>
.login-page{min-height:100dvh;display:flex;background:var(--bg)}
.login-hero{flex:1;display:flex;flex-direction:column;justify-content:center;padding:60px 64px;background:var(--surface);border-right:1px solid var(--border)}
.login-hero .brand{font-weight:700;font-size:22px;letter-spacing:-1.2px;margin-bottom:8px}
.login-hero h2{font-size:1.75rem;font-weight:650;margin:0 0 10px}
.subtitle{font-size:15px;color:var(--text-muted);line-height:1.6;max-width:400px;margin-bottom:32px}
.features{list-style:none;padding:0;font-size:14px;color:var(--text-muted);display:flex;flex-direction:column;gap:10px}
.features li{display:flex;align-items:center;gap:8px}
.features li::before{content:'';width:6px;height:6px;border-radius:50%;background:var(--accent);flex-shrink:0}
.hero-footer{margin-top:auto;padding-top:48px;display:flex;align-items:center;gap:12px}
.text-link{font-size:13px;color:var(--text-muted)}
.login-form-panel{width:420px;flex-shrink:0;display:flex;flex-direction:column;justify-content:center;padding:60px 48px}
.login-form-panel h3{font-size:1.25rem;font-weight:650;margin-bottom:24px}
.login-form{display:flex;flex-direction:column;gap:8px}
.login-dev-hint{margin-top:32px;padding:12px;background:var(--surface-elevated);border-radius:8px;font-size:12px;color:var(--text-muted);line-height:1.5}
.login-dev-hint code{font-family:var(--font-mono);font-size:12px}
@media(max-width:800px){.login-page{flex-direction:column}.login-hero{flex:none;padding:40px 28px 24px;border-right:0;border-bottom:1px solid var(--border)}.login-hero .features{display:none}.login-form-panel{width:100%;padding:32px 28px}}
</style>
