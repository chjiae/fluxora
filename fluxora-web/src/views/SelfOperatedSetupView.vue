<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const message = useMessage()
const step = ref(1)
const submitting = ref(false)
const tenantName = ref('Fluxora 自营')
const adminUsername = ref('tenantadmin')
const adminPassword = ref('')
const adminDisplayName = ref('自营管理员')

async function handleInit() {
  if (!tenantName.value || !adminUsername.value || !adminPassword.value || !adminDisplayName.value) { message.warning('请填写所有必填项'); return }
  submitting.value = true
  try { await auth.initSelfOperated({ tenantName: tenantName.value, adminUsername: adminUsername.value, adminPassword: adminPassword.value, adminDisplayName: adminDisplayName.value }); message.success('自营租户创建成功'); step.value = 2 }
  catch (e: any) { message.error(e.userMessage || '初始化失败') } finally { submitting.value = false }
}
</script>

<template>
  <div class="setup-page">
    <div class="setup-card">
      <h1 class="brand">fluxora<span>.</span></h1>
      <p class="subtitle">自营租户初始化</p>
      <n-steps :current="step" style="margin-bottom:24px"><n-step title="创建信息" /><n-step title="完成" /></n-steps>
      <div v-if="step===1">
        <p class="step-desc">创建自营租户（tenantCode: <n-tag size="small" bordered>default</n-tag>，类型: <n-tag size="small" type="info" bordered>SELF_OPERATED</n-tag>）及管理员账号。</p>
        <n-form label-placement="top">
          <n-form-item label="租户名称" required><n-input v-model:value="tenantName" /></n-form-item>
          <n-form-item label="管理员用户名" required><n-input v-model:value="adminUsername" /></n-form-item>
          <n-form-item label="管理员密码" required><n-input v-model:value="adminPassword" type="password" /></n-form-item>
          <n-form-item label="管理员显示名" required><n-input v-model:value="adminDisplayName" /></n-form-item>
        </n-form>
        <n-button type="primary" block :loading="submitting" @click="handleInit">创建自营租户</n-button>
      </div>
      <div v-else class="step-done">
        <div class="success-icon">&#10003;</div>
        <p class="success-msg">自营租户创建成功！</p>
        <p class="hint">现在可以进入控制台管理平台了。</p>
        <n-button type="primary" block @click="router.replace('/console/overview')">进入控制台</n-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.setup-page{min-height:100dvh;display:flex;align-items:center;justify-content:center;background:var(--bg);padding:24px}
.setup-card{width:440px;padding:40px 36px;border:1px solid var(--border);border-radius:12px;background:var(--surface)}
.brand{text-align:center;font-weight:700;font-size:22px;letter-spacing:-1.2px;margin-bottom:4px}
.subtitle{text-align:center;color:var(--text-muted);font-size:14px;margin-bottom:20px}
.step-desc{font-size:14px;color:var(--text-muted);margin-bottom:20px;line-height:1.6}
.step-done{text-align:center}
.success-icon{width:52px;height:52px;border-radius:50%;background:var(--accent);color:var(--bg);display:flex;align-items:center;justify-content:center;font-size:22px;margin:0 auto 14px}
.success-msg{font-size:17px;font-weight:650;margin-bottom:6px}
.hint{font-size:14px;color:var(--text-muted);margin-bottom:24px}
</style>
