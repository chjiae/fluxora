<script setup lang="ts">
import { h, onMounted, ref, watch } from 'vue'
import { NTag, NButton, NDropdown } from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { listTenants, createTenant, updateTenant, deleteTenant, enableTenant, disableTenant, setTenantExpire, type Tenant, type TenantPage } from '@/services/tenant'
import PageHeader from '@/components/PageHeader.vue'

const auth = useAuthStore()
const message = useMessage()
const dialog = useDialog()

const tenants = ref<Tenant[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const expireBefore = ref<number | null>(null)
const loading = ref(false)

const showCreateDrawer = ref(false)
const showDetailDrawer = ref(false)
const selectedTenant = ref<Tenant | null>(null)
const editMode = ref(false)
const showExpireEditor = ref(false)
const expireAtInput = ref<number | null>(null)
const formSubmitting = ref(false)

const createForm = ref({ tenantCode: '', name: '', description: '', type: 'STANDARD' })
const editForm = ref({ name: '', description: '' })
const createFormRef = ref<FormInst | null>(null)
const editFormRef = ref<FormInst | null>(null)
// 仅允许安全的中文校验反馈，避免把接口异常暴露到表单。
const createFormRules: FormRules = { tenantCode: [{ required: true, pattern: /^[a-z0-9-]+$/, message: '租户码仅支持小写字母、数字和连字符', trigger: ['input', 'blur'] }], name: [{ required: true, message: '请输入租户名称', trigger: ['input', 'blur'] }] }
const editFormRules: FormRules = { name: [{ required: true, message: '请输入租户名称', trigger: ['input', 'blur'] }] }

function statusLabel(s: string) { return ({ ENABLED: '启用', DISABLED: '停用', EXPIRED: '已过期' } as any)[s] || s }
function statusType(s: string): any { return ({ ENABLED: 'success', DISABLED: 'default', EXPIRED: 'warning' } as any)[s] || 'default' }

async function loadTenants() {
  loading.value = true
  try { const et = expireBefore.value ? new Date(expireBefore.value).toISOString().substring(0, 10) : undefined; const r: TenantPage = await listTenants({ keyword: keyword.value || undefined, type: typeFilter.value || undefined, status: statusFilter.value || undefined, expireTo: et, page: page.value, size: size.value }); tenants.value = r.items; total.value = r.total }
  catch { message.error('加载租户列表失败，请稍后重试') } finally { loading.value = false }
}
function resetFilters() { keyword.value = ''; typeFilter.value = ''; statusFilter.value = ''; expireBefore.value = null; page.value = 1; loadTenants() }
function openCreate() { createForm.value = { tenantCode: '', name: '', description: '', type: 'STANDARD' }; showCreateDrawer.value = true }
function openDetail(t: Tenant) { selectedTenant.value = t; editMode.value = false; showDetailDrawer.value = true }
function closeDetail() { showDetailDrawer.value = false; selectedTenant.value = null; editMode.value = false }

async function handleCreate() { try { await createFormRef.value?.validate() } catch { return }; formSubmitting.value = true; try { await createTenant({ ...createForm.value, enabled: true }); showCreateDrawer.value = false; message.success('租户已创建'); await loadTenants() } catch { message.error('创建租户失败，请稍后重试') } finally { formSubmitting.value = false } }
async function handleUpdate() { if (!selectedTenant.value) return; try { await editFormRef.value?.validate() } catch { return }; formSubmitting.value = true; try { await updateTenant(selectedTenant.value.id, editForm.value); closeDetail(); message.success('租户资料已保存'); await loadTenants() } catch { message.error('保存租户资料失败，请稍后重试') } finally { formSubmitting.value = false } }
async function doEnable(t: Tenant) { try { await enableTenant(t.id); message.success('租户已启用'); await loadTenants() } catch { message.error('启用租户失败，请稍后重试') } }
async function doDisable() { if (!selectedTenant.value || selectedTenant.value.type === 'SELF_OPERATED') return; try { await disableTenant(selectedTenant.value.id); closeDetail(); message.success('租户已停用'); await loadTenants() } catch { message.error('停用租户失败，请稍后重试') } }
async function doDelete() { if (!selectedTenant.value || selectedTenant.value.type === 'SELF_OPERATED') return; try { await deleteTenant(selectedTenant.value.id); closeDetail(); message.success('租户已删除'); await loadTenants() } catch { message.error('删除租户失败，请稍后重试') } }
function openExpireEditor() { if (!selectedTenant.value) return; expireAtInput.value = selectedTenant.value.expireAt ? new Date(selectedTenant.value.expireAt).getTime() : null; showExpireEditor.value = true }
async function saveExpireAt() { if (!selectedTenant.value || selectedTenant.value.type === 'SELF_OPERATED') return; formSubmitting.value = true; try { await setTenantExpire(selectedTenant.value.id, expireAtInput.value ? new Date(expireAtInput.value).toISOString() : null); showExpireEditor.value = false; closeDetail(); message.success('过期时间已更新'); await loadTenants() } catch { message.error('更新过期时间失败，请稍后重试') } finally { formSubmitting.value = false } }

function confirmDisable() { dialog.warning({ title: '确认停用', content: `停用后 ${selectedTenant.value?.name} 下所有用户将无法登录。确定停用？`, positiveText: '确认停用', negativeText: '取消', onPositiveClick: doDisable }) }
function confirmDelete() { dialog.error({ title: '确认删除', content: `删除 ${selectedTenant.value?.name} 后不可恢复。确定删除？`, positiveText: '确认删除', negativeText: '取消', onPositiveClick: doDelete }) }

const columns: DataTableColumns<Tenant> = [
  { title: '租户名称', key: 'name', minWidth: 180, render: (row) => h('button', { class: 'tenant-name-link', onClick: () => openDetail(row) }, row.name) },
  { title: '租户码', key: 'tenantCode', width: 130 },
  { title: '类型', key: 'type', width: 90, render: (row) => h(NTag, { size: 'small', bordered: false, type: row.type === 'SELF_OPERATED' ? 'info' : 'default' }, { default: () => row.type === 'SELF_OPERATED' ? '自营' : '标准' }) },
  { title: '状态', key: 'status', width: 90, render: (row) => h(NTag, { size: 'small', bordered: false, type: statusType(row.status) }, { default: () => statusLabel(row.status) }) },
  { title: '过期时间', key: 'expireAt', width: 120, render: row => row.expireAt?.slice(0, 10) || '永不过期' },
  { title: '更新时间', key: 'updatedAt', width: 120, render: row => row.updatedAt?.slice(0, 10) || '—' },
  { title: '操作', key: 'actions', width: 160, render: (row) => h('div', { class: 'table-actions' }, [h(NButton, { size: 'small', type: 'primary', onClick: () => openDetail(row) }, { default: () => '查看详情' }), h(NDropdown, { options: [{ label: '查看与编辑', key: 'detail' }], onSelect: () => openDetail(row) }, { default: () => h(NButton, { size: 'small' }, { default: () => '更多操作' }) })]) },
]

const typeOptions = [{ label: '全部类型', value: '' }, { label: '自营', value: 'SELF_OPERATED' }, { label: '标准', value: 'STANDARD' }]
const statusOptions = [{ label: '全部状态', value: '' }, { label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }, { label: '过期', value: 'EXPIRED' }]

watch([keyword, typeFilter, statusFilter, expireBefore], () => { page.value = 1; loadTenants() })
watch(page, () => loadTenants())
onMounted(() => loadTenants())
</script>

<template>
  <div class="tenant-page">
    <PageHeader title="租户管理" description="管理平台中的租户状态、资料和到期时间。"><n-button v-if="auth.canCreateTenant" type="primary" @click="openCreate">新增租户</n-button></PageHeader>
    <n-collapse style="margin-bottom:16px"><n-collapse-item title="筛选租户" name="filters"><n-form inline label-placement="left">
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <n-input v-model:value="keyword" placeholder="搜索..." clearable style="width:200px" />
      <n-select v-model:value="typeFilter" :options="typeOptions" style="width:100px" />
      <n-select v-model:value="statusFilter" :options="statusOptions" style="width:100px" />
      <n-date-picker v-model:value="expireBefore" type="date" clearable style="width:140px" />
      <n-button quaternary @click="resetFilters">重置</n-button>
    </div></n-form></n-collapse-item></n-collapse>
    <n-data-table :columns="columns" :data="tenants" :loading="loading" :pagination="false" :bordered="false" size="small" @row-click="openDetail" />
    <div v-if="!loading && tenants.length===0" style="padding:60px 0;text-align:center"><div style="font-size:16px;font-weight:600;margin-bottom:8px">暂无租户</div></div>
    <div v-if="total>size" style="display:flex;justify-content:center;margin-top:20px"><n-pagination v-model:page="page" :page-count="Math.ceil(total/size)" /></div>

    <!-- Create drawer -->
    <n-drawer v-model:show="showCreateDrawer" width="420" placement="right"><n-drawer-content title="新增租户" closable>
        <n-form ref="createFormRef" :model="createForm" :rules="createFormRules" label-placement="top">
          <n-form-item label="租户码" path="tenantCode"><n-input v-model:value="createForm.tenantCode" placeholder="例如 team-alpha（创建后不可修改）" /></n-form-item>
          <n-form-item label="名称" path="name"><n-input v-model:value="createForm.name" placeholder="请输入租户显示名称" /></n-form-item>
          <n-form-item label="描述"><n-input v-model:value="createForm.description" placeholder="可选描述" type="textarea" /></n-form-item>
          <n-form-item label="类型"><n-select v-model:value="createForm.type" :options="[{label:'标准',value:'STANDARD'}]" /></n-form-item>
        </n-form>
        <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px"><n-button @click="showCreateDrawer=false">取消</n-button><n-button type="primary" :loading="formSubmitting" @click="handleCreate">创建租户</n-button></div>
        </n-drawer-content></n-drawer>

    <!-- Detail drawer -->
    <template v-if="selectedTenant">
    <n-drawer :show="showDetailDrawer" width="420" placement="right" @update:show="(v: boolean) => { if (!v) closeDetail() }"><n-drawer-content title="租户详情" closable>
        <template v-if="!editMode">
          <div class="dl">租户码</div><code>{{selectedTenant.tenantCode}}</code>
          <div class="dl">名称</div><div>{{selectedTenant.name}}</div>
          <div class="dl">描述</div><div>{{selectedTenant.description||'—'}}</div>
          <div class="dl">类型</div><n-tag :type="selectedTenant.type==='SELF_OPERATED'?'info':'default'" size="small" bordered>{{selectedTenant.type==='SELF_OPERATED'?'自营':'标准'}}</n-tag>
          <div class="dl">状态</div><n-tag :type="statusType(selectedTenant.status)" size="small" bordered>{{statusLabel(selectedTenant.status)}}</n-tag>
          <div class="dl">过期</div><div>{{selectedTenant.expireAt?selectedTenant.expireAt.substring(0,10):'永不过期'}}</div>
          <n-divider />
          <n-button v-if="auth.canUpdateTenant" type="primary" @click="editForm={name:selectedTenant.name,description:selectedTenant.description||''};editMode=true">编辑资料</n-button>
          <template v-if="selectedTenant.type!=='SELF_OPERATED'">
            <n-divider />
            <div style="display:flex;gap:8px;flex-wrap:wrap"><n-button v-if="selectedTenant.status==='DISABLED'&&auth.canEnableTenant" @click="doEnable(selectedTenant)">启用</n-button><n-button v-if="selectedTenant.status==='ENABLED'&&auth.canDisableTenant" @click="confirmDisable">停用</n-button><n-button v-if="auth.canSetTenantExpire" @click="openExpireEditor">设置过期时间</n-button></div>
            <n-divider />
            <n-button v-if="auth.canDeleteTenant" type="error" @click="confirmDelete">删除</n-button>
          </template>
          <n-alert v-else type="info" style="margin-top:12px">自营租户受保护：不能停用、删除或设置过期时间。</n-alert>
        </template>
        <template v-else>
          <n-form ref="editFormRef" :model="editForm" :rules="editFormRules" label-placement="top">
            <n-form-item label="名称" path="name"><n-input v-model:value="editForm.name" /></n-form-item>
            <n-form-item label="描述"><n-input v-model:value="editForm.description" type="textarea" /></n-form-item>
          </n-form>
          <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px"><n-button @click="editMode=false">取消</n-button><n-button type="primary" :loading="formSubmitting" @click="handleUpdate">保存</n-button></div>
        </template>
      </n-drawer-content></n-drawer>
    </template>

    <n-drawer :show="showExpireEditor" width="420" placement="right" @update:show="(v: boolean) => { showExpireEditor = v }"><n-drawer-content title="设置过期时间" closable>
        <p class="drawer-hint">留空表示租户永不过期。此操作会立即影响该租户用户的登录与访问资格。</p>
        <n-date-picker v-model:value="expireAtInput" type="datetime" clearable style="width:100%;margin-top:16px" />
        <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:24px"><n-button @click="showExpireEditor=false">取消</n-button><n-button type="primary" :loading="formSubmitting" @click="saveExpireAt">保存过期时间</n-button></div>
      </n-drawer-content></n-drawer>
  </div>
</template>

<style scoped>
.tenant-page{ }
.pg-hdr{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px}
.pg-hdr h2{margin:0;font-size:1.25rem;font-weight:650}
.dl{font-size:12px;font-weight:600;color:var(--text-muted);margin:12px 0 2px}
.tenant-name-link{padding:0;border:0;background:transparent;color:var(--text);font:inherit;font-weight:600;cursor:pointer;text-align:left}
.tenant-name-link:hover{color:var(--accent)}
.drawer-hint{margin:0;color:var(--text-muted);font-size:13px;line-height:1.6}
:deep(.n-data-table-tr){cursor:pointer}
:deep(.n-data-table-tr:hover){background:var(--surface-elevated)}
code{background:var(--surface-elevated);padding:2px 6px;border-radius:4px;border:1px solid var(--border);font-family:var(--font-mono),monospace;font-size:12px}
</style>
