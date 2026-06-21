<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  listTenants, createTenant, updateTenant, deleteTenant, toggleTenant,
  type Tenant, type TenantPage,
} from '@/services/tenant'
import { Search, Plus, Pencil, Trash2, Power, PowerOff, X, AlertTriangle } from 'lucide-vue-next'

const auth = useAuthStore()

const tenants = ref<Tenant[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const typeFilter = ref('')
const enabledFilter = ref<boolean | null>(null)

const loading = ref(false)
const errorMsg = ref('')
const toastMsg = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null

const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const showDeleteConfirm = ref(false)
const selectedTenant = ref<Tenant | null>(null)

const form = ref({ tenantCode: '', name: '', type: 'THIRD_PARTY', enabled: true, expireAt: '' })
const formError = ref('')
const formSubmitting = ref(false)

const totalPages = computed(() => Math.ceil(total.value / size.value))

async function loadTenants() {
  loading.value = true
  errorMsg.value = ''
  try {
    const result: TenantPage = await listTenants({
      keyword: keyword.value || undefined,
      type: typeFilter.value || undefined,
      enabled: enabledFilter.value,
      page: page.value,
      size: size.value,
    })
    tenants.value = result.items
    total.value = result.total
  } catch (e: any) {
    errorMsg.value = e.userMessage || '加载租户列表失败'
  } finally {
    loading.value = false
  }
}

function showToast(msg: string) {
  toastMsg.value = msg
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastMsg.value = '' }, 3000)
}

function resetFilters() {
  keyword.value = ''
  typeFilter.value = ''
  enabledFilter.value = null
  page.value = 1
  loadTenants()
}

function openCreate() {
  form.value = { tenantCode: '', name: '', type: 'THIRD_PARTY', enabled: true, expireAt: '' }
  formError.value = ''
  showCreateDialog.value = true
}

function openEdit(tenant: Tenant) {
  selectedTenant.value = tenant
  form.value = {
    tenantCode: tenant.tenantCode,
    name: tenant.name,
    type: tenant.type,
    enabled: tenant.enabled,
    expireAt: tenant.expireAt ? tenant.expireAt.substring(0, 10) : '',
  }
  formError.value = ''
  showEditDialog.value = true
}

async function handleCreate() {
  if (!form.value.tenantCode || !form.value.name) {
    formError.value = '租户码和名称不能为空'
    return
  }
  formSubmitting.value = true
  formError.value = ''
  try {
    await createTenant({ ...form.value, type: form.value.type, enabled: form.value.enabled })
    showCreateDialog.value = false
    showToast('租户创建成功')
    loadTenants()
  } catch (e: any) {
    formError.value = e.userMessage || '创建失败'
  } finally {
    formSubmitting.value = false
  }
}

async function handleUpdate() {
  if (!form.value.name) {
    formError.value = '名称不能为空'
    return
  }
  formSubmitting.value = true
  formError.value = ''
  try {
    await updateTenant(selectedTenant.value!.id, {
      name: form.value.name,
      enabled: form.value.enabled,
      expireAt: form.value.expireAt ? form.value.expireAt + 'T23:59:59Z' : null,
    })
    showEditDialog.value = false
    showToast('租户更新成功')
    loadTenants()
  } catch (e: any) {
    formError.value = e.userMessage || '更新失败'
  } finally {
    formSubmitting.value = false
  }
}

async function handleToggle(tenant: Tenant) {
  try {
    await toggleTenant(tenant.id, !tenant.enabled)
    showToast(tenant.enabled ? '租户已停用' : '租户已启用')
    loadTenants()
  } catch (e: any) {
    showToast(e.userMessage || '操作失败')
  }
}

function confirmDelete(tenant: Tenant) {
  selectedTenant.value = tenant
  showDeleteConfirm.value = true
}

async function handleDelete() {
  if (!selectedTenant.value) return
  formSubmitting.value = true
  try {
    await deleteTenant(selectedTenant.value.id)
    showDeleteConfirm.value = false
    showToast('租户已删除')
    loadTenants()
  } catch (e: any) {
    showToast(e.userMessage || '删除失败')
  } finally {
    formSubmitting.value = false
  }
}

function isSelfOperated(tenant: Tenant) {
  return tenant.type === 'SELF_OPERATED'
}

watch([keyword, typeFilter, enabledFilter], () => {
  page.value = 1
  loadTenants()
})

watch(page, () => loadTenants())

onMounted(() => loadTenants())
</script>

<template>
  <div class="tenant-mgmt">
    <div class="page-header">
      <h2>租户管理</h2>
      <button class="primary" @click="openCreate">
        <Plus :size="16" /> 新增租户
      </button>
    </div>

    <!-- 筛选栏 -->
    <div class="filters">
      <div class="search-box">
        <Search :size="16" />
        <input v-model="keyword" placeholder="搜索租户码或名称..." />
      </div>
      <select v-model="typeFilter">
        <option value="">全部类型</option>
        <option value="SELF_OPERATED">自营</option>
        <option value="THIRD_PARTY">第三方</option>
      </select>
      <select v-model="enabledFilter">
        <option :value="null">全部状态</option>
        <option :value="true">已启用</option>
        <option :value="false">已停用</option>
      </select>
      <button class="btn-text" @click="resetFilters">重置</button>
    </div>

    <!-- 错误提示 -->
    <p v-if="errorMsg" class="error-banner">{{ errorMsg }}</p>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading">加载中...</div>

    <!-- 空状态 -->
    <div v-else-if="tenants.length === 0" class="empty-state">
      <div>暂无租户</div>
      <p class="muted">点击"新增租户"创建第一个租户。</p>
    </div>

    <!-- 租户列表表格 -->
    <table v-else class="tenant-table">
      <thead>
        <tr>
          <th>租户码</th>
          <th>名称</th>
          <th>类型</th>
          <th>状态</th>
          <th>过期时间</th>
          <th>创建时间</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in tenants" :key="t.id">
          <td class="code">{{ t.tenantCode }}</td>
          <td>{{ t.name }}</td>
          <td>
            <span class="badge" :class="t.type === 'SELF_OPERATED' ? 'self' : 'third'">
              {{ t.type === 'SELF_OPERATED' ? '自营' : '第三方' }}
            </span>
          </td>
          <td>
            <span class="badge" :class="t.enabled ? 'on' : 'off'">
              {{ t.enabled ? '启用' : '停用' }}
            </span>
          </td>
          <td>{{ t.expireAt ? t.expireAt.substring(0, 10) : '—' }}</td>
          <td>{{ t.createdAt ? t.createdAt.substring(0, 10) : '—' }}</td>
          <td class="actions">
            <button class="icon-btn" title="编辑" @click="openEdit(t)">
              <Pencil :size="15" />
            </button>
            <button
              class="icon-btn"
              :title="t.enabled ? '停用' : '启用'"
              :disabled="isSelfOperated(t)"
              @click="handleToggle(t)"
            >
              <PowerOff v-if="t.enabled" :size="15" />
              <Power v-else :size="15" />
            </button>
            <button
              class="icon-btn danger"
              title="删除"
              :disabled="isSelfOperated(t)"
              @click="confirmDelete(t)"
            >
              <Trash2 :size="15" />
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 分页 -->
    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="page <= 1" @click="page--">上一页</button>
      <span>{{ page }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages" @click="page++">下一页</button>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="showCreateDialog || showEditDialog" class="dialog-overlay" @click.self="showCreateDialog = false; showEditDialog = false">
      <div class="dialog">
        <div class="dialog-header">
          <h3>{{ showCreateDialog ? '新增租户' : '编辑租户' }}</h3>
          <button class="icon-btn" @click="showCreateDialog = false; showEditDialog = false">
            <X :size="18" />
          </button>
        </div>
        <form @submit.prevent="showCreateDialog ? handleCreate() : handleUpdate()">
          <div v-if="showCreateDialog" class="field">
            <label>租户码</label>
            <input v-model="form.tenantCode" type="text" placeholder="唯一标识，如 acme-corp" :disabled="formSubmitting" />
          </div>
          <div class="field">
            <label>名称</label>
            <input v-model="form.name" type="text" placeholder="租户显示名称" :disabled="formSubmitting" />
          </div>
          <div v-if="showCreateDialog" class="field">
            <label>类型</label>
            <select v-model="form.type" :disabled="formSubmitting">
              <option value="THIRD_PARTY">第三方</option>
              <option value="SELF_OPERATED">自营</option>
            </select>
          </div>
          <div class="field">
            <label>状态</label>
            <select v-model="form.enabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </div>
          <div v-if="showEditDialog" class="field">
            <label>过期时间</label>
            <input v-model="form.expireAt" type="date" :disabled="formSubmitting" />
          </div>
          <p v-if="formError" class="error-msg">{{ formError }}</p>
          <div class="dialog-actions">
            <button type="button" class="btn-text" @click="showCreateDialog = false; showEditDialog = false">取消</button>
            <button type="submit" class="primary" :disabled="formSubmitting">
              {{ formSubmitting ? '提交中...' : (showCreateDialog ? '创建' : '保存') }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="showDeleteConfirm" class="dialog-overlay" @click.self="showDeleteConfirm = false">
      <div class="dialog dialog-sm">
        <div class="dialog-header">
          <h3><AlertTriangle :size="18" /> 确认删除</h3>
        </div>
        <p>确定要删除租户 <b>{{ selectedTenant?.name }}</b> 吗？删除后不可恢复。</p>
        <div class="dialog-actions">
          <button class="btn-text" @click="showDeleteConfirm = false">取消</button>
          <button class="primary danger-btn" :disabled="formSubmitting" @click="handleDelete">
            {{ formSubmitting ? '删除中...' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Toast -->
    <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
  </div>
</template>

<style scoped>
.tenant-mgmt { padding: 0; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-header h2 { margin: 0; font-size: 20px; font-weight: 700; }
.primary {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border: none; border-radius: 6px;
  background: #151515; color: #f5f4f0; font-size: 13px; font-weight: 600;
  cursor: pointer;
}
.primary:disabled { opacity: .5; cursor: not-allowed; }
.filters {
  display: flex; gap: 8px; align-items: center; margin-bottom: 16px; flex-wrap: wrap;
}
.search-box {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px;
  background: #fff; flex: 1; min-width: 180px;
}
.search-box input { border: none; outline: none; font-size: 13px; width: 100%; background: none; }
.filters select {
  padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px;
  font-size: 13px; background: #fff; outline: none;
}
.btn-text {
  padding: 6px 12px; border: none; background: none;
  color: var(--accent); font-size: 13px; cursor: pointer;
}
.error-msg { color: #d92d20; font-size: 13px; margin: 4px 0 0; }
.error-banner {
  padding: 10px 14px; border-radius: 6px; background: #fef2f2;
  color: #d92d20; font-size: 13px; margin: 0 0 12px;
}
.loading { padding: 40px 0; text-align: center; color: var(--muted); font-size: 14px; }
.empty-state { padding: 60px 0; text-align: center; }
.empty-state div { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.tenant-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.tenant-table th { text-align: left; padding: 10px 12px; border-bottom: 2px solid var(--border); font-weight: 600; color: var(--muted); font-size: 12px; }
.tenant-table td { padding: 10px 12px; border-bottom: 1px solid var(--border); }
.tenant-table .code { font-family: monospace; font-weight: 600; }
.badge {
  display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 600;
}
.badge.self { background: #dcfce7; color: #166534; }
.badge.third { background: #dbeafe; color: #1e40af; }
.badge.on { background: #dcfce7; color: #166534; }
.badge.off { background: #f3f4f6; color: #6b7280; }
.actions { display: flex; gap: 4px; }
.icon-btn {
  display: flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border: 1px solid var(--border); border-radius: 6px;
  background: none; cursor: pointer; color: var(--muted); transition: color .15s;
}
.icon-btn:hover:not(:disabled) { color: var(--text); }
.icon-btn:disabled { opacity: .3; cursor: not-allowed; }
.icon-btn.danger:hover:not(:disabled) { color: #d92d20; border-color: #d92d20; }
.pagination { display: flex; justify-content: center; gap: 16px; align-items: center; margin-top: 16px; font-size: 13px; }
.pagination button { padding: 4px 12px; border: 1px solid var(--border); border-radius: 6px; background: none; cursor: pointer; font-size: 13px; }
.pagination button:disabled { opacity: .3; cursor: not-allowed; }
.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,.3);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.dialog {
  background: #fff; border-radius: 10px; padding: 24px;
  width: 440px; max-width: 90vw; box-shadow: 0 20px 60px rgba(0,0,0,.15);
}
.dialog-sm { width: 360px; }
.dialog-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.dialog-header h3 { margin: 0; font-size: 16px; display: flex; align-items: center; gap: 8px; }
.dialog p { font-size: 14px; color: var(--muted); margin: 0 0 20px; }
.dialog form { display: flex; flex-direction: column; gap: 14px; }
.dialog .field { display: flex; flex-direction: column; gap: 4px; }
.dialog .field label { font-size: 13px; font-weight: 600; }
.dialog .field input, .dialog .field select {
  padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; outline: none;
}
.dialog .field input:focus, .dialog .field select:focus { border-color: var(--accent); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.danger-btn { background: #d92d20 !important; }
.toast {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  padding: 10px 24px; border-radius: 8px; background: #151515; color: #f5f4f0;
  font-size: 14px; z-index: 200; box-shadow: 0 4px 12px rgba(0,0,0,.2);
}
.muted { color: var(--muted); font-size: 13px; }
</style>
