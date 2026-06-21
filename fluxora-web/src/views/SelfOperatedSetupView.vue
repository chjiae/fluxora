<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const tenantName = ref('Fluxora 自营')
const adminUsername = ref('tenantadmin')
const adminPassword = ref('')
const adminDisplayName = ref('自营管理员')
const submitting = ref(false)
const errorMsg = ref('')
const step = ref(1)

async function handleInit() {
  if (!tenantName.value || !adminUsername.value || !adminPassword.value || !adminDisplayName.value) {
    errorMsg.value = '请填写所有必填项'
    return
  }
  submitting.value = true
  errorMsg.value = ''
  try {
    await auth.initSelfOperated({
      tenantName: tenantName.value,
      adminUsername: adminUsername.value,
      adminPassword: adminPassword.value,
      adminDisplayName: adminDisplayName.value,
    })
    step.value = 2
  } catch (e: any) {
    errorMsg.value = auth.error || '初始化失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

function goConsole() {
  router.replace('/console/overview')
}
</script>

<template>
  <div class="setup-page">
    <div class="setup-card">
      <h1 class="brand">fl<span>u</span>xora</h1>
      <p class="subtitle">自营租户初始化</p>

      <!-- Step 1: 创建信息 -->
      <div v-if="step === 1" class="step">
        <p class="step-desc">首次访问需要创建自营租户及其管理员账号。</p>
        <form @submit.prevent="handleInit" class="setup-form">
          <div class="field">
            <label for="tenantName">租户名称</label>
            <input id="tenantName" v-model="tenantName" type="text" :disabled="submitting" />
          </div>
          <div class="field">
            <label for="adminUser">管理员用户名</label>
            <input id="adminUser" v-model="adminUsername" type="text" :disabled="submitting" />
          </div>
          <div class="field">
            <label for="adminPass">管理员密码</label>
            <input id="adminPass" v-model="adminPassword" type="password" :disabled="submitting" />
          </div>
          <div class="field">
            <label for="adminDisplay">管理员显示名</label>
            <input id="adminDisplay" v-model="adminDisplayName" type="text" :disabled="submitting" />
          </div>
          <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
          <button type="submit" class="primary" :disabled="submitting">
            {{ submitting ? '初始化中...' : '创建自营租户' }}
          </button>
        </form>
      </div>

      <!-- Step 2: 完成 -->
      <div v-else class="step">
        <div class="success-icon">&#10003;</div>
        <p class="success-msg">自营租户创建成功！</p>
        <p class="hint">现在可以进入控制台管理平台了。</p>
        <button class="primary" @click="goConsole">进入控制台</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.setup-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}
.setup-card {
  width: 420px;
  padding: 40px 32px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
}
.setup-card .brand {
  text-align: center;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -1.8px;
  margin: 0 0 4px;
}
.setup-card .brand span { color: var(--accent); }
.subtitle { text-align: center; color: var(--muted); font-size: 14px; margin: 0 0 24px; }
.step-desc { font-size: 14px; color: var(--muted); margin: 0 0 20px; text-align: center; }
.setup-form { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 4px; }
.field label { font-size: 13px; font-weight: 600; color: var(--text); }
.field input {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 14px;
  outline: none;
}
.field input:focus { border-color: var(--accent); }
.error-msg { color: #d92d20; font-size: 13px; margin: 0; }
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
}
.primary:disabled { opacity: .5; cursor: not-allowed; }
.success-icon {
  width: 56px; height: 56px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  margin: 0 auto 16px;
}
.success-msg { font-size: 18px; font-weight: 700; text-align: center; margin: 0 0 8px; }
.hint { font-size: 14px; color: var(--muted); text-align: center; margin: 0 0 24px; }
</style>
