<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)
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
    <div class="login-card">
      <h1 class="brand">fl<span>u</span>xora</h1>
      <p class="subtitle">平台控制台</p>
      <form @submit.prevent="handleLogin" class="login-form">
        <div class="field">
          <label for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="请输入用户名"
            :disabled="submitting"
          />
        </div>
        <div class="field">
          <label for="password">密码</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            :disabled="submitting"
          />
        </div>
        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
        <button type="submit" class="primary" :disabled="submitting">
          {{ submitting ? '登录中...' : '登录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}
.login-card {
  width: 360px;
  padding: 40px 32px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
}
.login-card .brand {
  text-align: center;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -1.8px;
  margin: 0 0 4px;
}
.login-card .brand span { color: var(--accent); }
.login-card .subtitle {
  text-align: center;
  color: var(--muted);
  font-size: 14px;
  margin: 0 0 28px;
}
.login-form { display: flex; flex-direction: column; gap: 16px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.field label { font-size: 13px; font-weight: 600; color: var(--text); }
.field input {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 14px;
  outline: none;
  transition: border-color .15s;
}
.field input:focus { border-color: var(--accent); }
.error-msg {
  color: #d92d20;
  font-size: 13px;
  margin: 0;
}
.primary {
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: 6px;
  background: #151515;
  color: #f5f4f0;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity .15s;
}
.primary:disabled { opacity: .5; cursor: not-allowed; }
</style>
