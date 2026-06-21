<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { Sun, Moon, Eye, EyeOff } from 'lucide-vue-next'

const router = useRouter()
const auth = useAuthStore()
const themeStore = useThemeStore()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const isDev = import.meta.env.DEV
const errorMsg = ref('')

async function handleLogin() {
  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户名和密码'
    return
  }
  submitting.value = true
  errorMsg.value = ''
  try {
    await auth.loginAction(username.value, password.value)
    if (auth.isPlatformAdmin) {
      await auth.checkSelfOperatedStatus()
      if (!auth.selfOperatedInitialized) {
        router.replace('/console/setup')
      } else {
        router.replace('/console/overview')
      }
    } else {
      router.replace('/console/overview')
    }
  } catch (e: any) {
    errorMsg.value = auth.error || '用户名或密码错误，请重新输入'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 左侧：产品介绍 -->
    <div class="login-hero">
      <RouterLink to="/" class="brand">fluxora<span>.</span></RouterLink>
      <h2>API 中转平台</h2>
      <p class="subtitle">统一接入多上游、多协议与流式 API，为开发者提供稳定、可靠的中转服务。</p>
      <ul class="features">
        <li>多租户管理与权限隔离</li>
        <li>OpenAI / Anthropic 协议兼容</li>
        <li>流式中继与用量治理</li>
      </ul>
      <div style="margin-top:auto;padding-top:48px;display:flex;align-items:center;gap:12px">
        <button class="theme-toggle" :aria-label="themeStore.theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'" @click="themeStore.toggle()">
          <Sun v-if="themeStore.theme === 'dark'" :size="16" />
          <Moon v-else :size="16" />
        </button>
        <RouterLink to="/docs" style="font-size:13px;color:var(--text-muted)">文档</RouterLink>
        <RouterLink to="/" style="font-size:13px;color:var(--text-muted)">官网</RouterLink>
      </div>
    </div>

    <!-- 右侧：登录表单 -->
    <div class="login-form-panel">
      <h3>登录控制台</h3>
      <form @submit.prevent="handleLogin" class="login-form">
        <div class="field">
          <label for="username">用户名</label>
          <input
            id="username" v-model="username" type="text"
            autocomplete="username" placeholder="请输入用户名"
            :disabled="submitting"
          />
        </div>
        <div class="field">
          <label for="password">密码</label>
          <div class="input-wrapper">
            <input
              id="password" v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password" placeholder="请输入密码"
              :disabled="submitting"
            />
            <button type="button" class="toggle-pass" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              <EyeOff v-if="showPassword" :size="16" />
              <Eye v-else :size="16" />
            </button>
          </div>
        </div>
        <p v-if="errorMsg" class="error-msg" role="alert">{{ errorMsg }}</p>
        <button type="submit" class="primary" :disabled="submitting">
          {{ submitting ? '登录中...' : '登录' }}
        </button>
      </form>
      <div class="login-dev-hint" v-if="isDev">
        本地开发初始账号：<code>admin</code> / 密码见环境变量 <code>INIT_ADMIN_PASSWORD</code>
      </div>
    </div>
  </div>
</template>
