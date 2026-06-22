<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { NButton, NDropdown, NIcon, useDialog, useMessage } from 'naive-ui'
import { MoreHorizontal, Plus, Search, X } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import StatusDot from '@/components/StatusDot.vue'
import {
  createProvider, deleteProvider, getProviderStats, listProviders,
  setProviderEnabled, updateProvider,
  type ProviderSummary, type ProviderStats, type ScopeType,
} from '@/services/upstream'
import { listTenants, type Tenant } from '@/services/tenant'

const auth = useAuthStore()
const dialog = useDialog()
const message = useMessage()

const rows = ref<ProviderSummary[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const scopeFilter = ref<ScopeType | ''>('')
const statusFilter = ref<boolean | ''>('')

const stats = ref<ProviderStats | null>(null)
const statsLoading = ref(false)

const editorOpen = ref(false)
const editing = ref<ProviderSummary | null>(null)
const saving = ref(false)
const tenants = ref<Tenant[]>([])
const form = reactive({
  name: '', code: '', scopeType: 'TENANT_PRIVATE' as ScopeType,
  description: '', enabled: true, tenantId: null as number | null,
})

const metricItems = computed(() => [
  { label: '厂商总数', value: stats.value?.total ?? null },
  { label: '平台共享', value: stats.value?.platformShared ?? null },
  { label: '租户私有', value: stats.value?.tenantPrivate ?? null },
  { label: '已停用', value: stats.value?.disabled ?? null, tone: 'warn' as const },
])

const hasFilter = computed(() => !!keyword.value || scopeFilter.value !== '' || statusFilter.value !== '')
const isSharedReadOnly = computed(() => !!editing.value && editing.value.scopeType === 'PLATFORM_SHARED' && !auth.isPlatformAdmin)

function scopeLabel(v: string) { return v === 'PLATFORM_SHARED' ? '平台共享' : '租户私有' }
function canManage(r: ProviderSummary) {
  if (r.scopeType === 'PLATFORM_SHARED') return auth.isPlatformAdmin
  return auth.isPlatformAdmin || (auth.isTenantAdmin && r.tenantId === auth.user?.tenantId)
}

async function loadList() {
  loading.value = true
  try {
    const result = await listProviders({
      keyword: keyword.value || undefined, scopeType: scopeFilter.value || undefined,
      enabled: statusFilter.value === '' ? undefined : statusFilter.value, page: page.value, size,
    })
    rows.value = result.items; total.value = result.total
  } catch (e: any) { message.error(e.userMessage || '加载失败，请稍后重试') }
  finally { loading.value = false }
}

async function loadStats() {
  statsLoading.value = true
  try { stats.value = await getProviderStats() } catch { /* 静默 */ }
  finally { statsLoading.value = false }
}

async function refreshAfterWrite() { await Promise.all([loadList(), loadStats()]) }
function resetFilters() { keyword.value = ''; scopeFilter.value = ''; statusFilter.value = ''; page.value = 1; void loadList() }

function openEditor(r?: ProviderSummary) {
  if (r) {
    editing.value = r
    Object.assign(form, { name: r.name, code: r.code, scopeType: r.scopeType, description: r.description || '', enabled: r.status === 'ENABLED', tenantId: r.tenantId })
  } else {
    editing.value = null
    Object.assign(form, { name: '', code: '', scopeType: auth.isPlatformAdmin ? 'PLATFORM_SHARED' : 'TENANT_PRIVATE', description: '', enabled: true, tenantId: null })
    if (auth.isPlatformAdmin) { listTenants({ page: 1, size: 200 }).then(r => { tenants.value = r.items }).catch(() => {}) }
  }
  editorOpen.value = true
}

async function save() {
  if (!form.name.trim() || (!editing.value && !form.code.trim())) { message.warning('请填写厂商名称和编码'); return }
  if (form.scopeType === 'TENANT_PRIVATE' && auth.isPlatformAdmin && !form.tenantId) { message.warning('请选择私有厂商归属的租户'); return }
  saving.value = true
  try {
    if (editing.value) {
      await updateProvider(editing.value.id, { name: form.name.trim(), description: form.description.trim() || undefined })
      message.success('上游厂商已更新')
    } else {
      await createProvider({ name: form.name.trim(), code: form.code.trim(), scopeType: form.scopeType, description: form.description.trim() || undefined, enabled: form.enabled, tenantId: form.scopeType === 'TENANT_PRIVATE' ? form.tenantId : null })
      message.success('上游厂商已创建')
    }
    editorOpen.value = false; await refreshAfterWrite()
  } catch (e: any) { message.error(e.userMessage || '保存失败，请稍后重试') }
  finally { saving.value = false }
}

async function toggle(r: ProviderSummary) {
  const enable = r.status !== 'ENABLED'
  try { await setProviderEnabled(r.id, enable); message.success(enable ? '已启用' : '已停用'); await refreshAfterWrite() }
  catch (e: any) { message.error(e.userMessage || '状态更新失败，请稍后重试') }
}

function confirmDelete(r: ProviderSummary) {
  dialog.warning({
    title: '删除上游厂商', content: `确定删除「${r.name}」吗？若仍被接入地址引用将无法删除。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try { await deleteProvider(r.id); message.success('已删除'); await refreshAfterWrite() }
      catch (e: any) { message.error(e.userMessage || '删除失败，请稍后重试') }
    },
  })
}

// ---- kebab 操作菜单 ----
function rowMenuOpts(r: ProviderSummary) {
  const m = canManage(r)
  const opts: { key: string; label: string; disabled?: boolean }[] = []
  if (auth.canUpdateUpstream) opts.push({ key: 'manage', label: '管理' })
  if (auth.canEnableUpstream || auth.canDisableUpstream) opts.push({ key: 'toggle', label: r.status === 'ENABLED' ? '停用' : '启用', disabled: !m })
  if (auth.canDeleteUpstream) opts.push({ key: 'delete', label: '删除', disabled: !m })
  return opts
}

function handleRowMenu(key: string, r: ProviderSummary) {
  if (key === 'manage') openEditor(r)
  else if (key === 'toggle') toggle(r)
  else if (key === 'delete') confirmDelete(r)
}

function rowProps(r: ProviderSummary) {
  return { style: 'cursor: pointer', onClick: () => openEditor(r) }
}

// ---- 表格列 ----
const columns = computed<any>(() => [
  { title: '厂商', key: 'name', minWidth: 170, render: (r: ProviderSummary) => h('div', { class: 'cell-duo' }, [h('div', { class: 'cell-primary' }, r.name), h('div', { class: 'cell-meta' }, r.code)]) },
  { title: '范围', key: 'scopeType', width: 110, render: (r: ProviderSummary) => h('span', { class: r.scopeType === 'PLATFORM_SHARED' ? 'chip-shared' : 'chip-private' }, scopeLabel(r.scopeType)) },
  ...(auth.isPlatformAdmin ? [{ title: '所属租户', key: 'tenantName', width: 140, render: (r: ProviderSummary) => r.tenantName || '—' }] : []),
  { title: '状态', key: 'status', width: 100, render: (r: ProviderSummary) => h(StatusDot, { status: r.status }) },
  { title: '描述', key: 'description', minWidth: 180, ellipsis: { tooltip: true }, render: (r: ProviderSummary) => r.description || '—' },
  {
    title: '', key: '__row_actions', width: 44, align: 'right', className: 'col-row-actions',
    render: (r: ProviderSummary) => {
      const opts = rowMenuOpts(r)
      if (!opts.length) return null
      return h(NDropdown, { trigger: 'click', placement: 'bottom-end', options: opts, onSelect: (k: string) => handleRowMenu(k, r) }, {
        default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作', onClick: (e: MouseEvent) => e.stopPropagation() }, { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) }),
      })
    },
  },
])

onMounted(() => { void loadList(); void loadStats() })
</script>

<template>
  <section class="provider-page">
    <header class="page-hdr">
      <div class="page-hdr-text"><h1>上游厂商</h1><p>管理平台共享或当前租户私有的上游服务来源。</p></div>
      <n-button v-if="auth.canCreateUpstream" type="primary" @click="openEditor()">
        <template #icon><n-icon><Plus /></n-icon></template>新增厂商
      </n-button>
    </header>

    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索名称或厂商编码" @keyup.enter="page = 1; loadList()" />
        <button v-if="keyword" type="button" class="search-clear" @click="keyword = ''; page = 1; loadList()"><n-icon :size="14"><X /></n-icon></button>
      </label>
      <div class="chip-group">
        <button v-for="o in [{l:'全部',v:''},{l:'平台共享',v:'PLATFORM_SHARED'},{l:'租户私有',v:'TENANT_PRIVATE'}]" :key="'sc'+o.v" type="button" class="chip" :class="{'is-active':scopeFilter===o.v}" @click="scopeFilter = o.v as ScopeType|''; page = 1; loadList()">{{ o.l }}</button>
      </div>
      <div class="chip-group">
        <button v-for="o in [{l:'全部',v:''},{l:'启用',v:true},{l:'停用',v:false}]" :key="'st'+String(o.v)" type="button" class="chip" :class="{'is-active':statusFilter===o.v}" @click="statusFilter = o.v as boolean|''; page = 1; loadList()">{{ o.l }}</button>
      </div>
      <button v-if="hasFilter" type="button" class="reset-btn" @click="resetFilters">重置</button>
    </div>

    <div class="table-region">
      <n-data-table v-if="rows.length || loading" :columns="columns" :data="rows" :loading="loading" :pagination="false" :bordered="false" :single-line="false" :row-props="rowProps" :scroll-x="900" flex-height size="medium" />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有符合筛选条件的厂商' : '还没有上游厂商'">
          <template #extra><n-button v-if="!hasFilter && auth.canCreateUpstream" type="primary" @click="openEditor()">新增第一个厂商</n-button></template>
        </n-empty>
      </div>
    </div>

    <footer class="page-foot">
      <span>共 <strong>{{ total }}</strong> 个厂商</span>
      <n-pagination v-model:page="page" :item-count="total" :page-size="size" @update:page="loadList" />
    </footer>

    <n-modal v-model:show="editorOpen" preset="card" :title="editing ? '管理上游厂商' : '新增上游厂商'" style="max-width: 520px">
      <n-alert v-if="isSharedReadOnly" type="info" :bordered="false" style="margin-bottom:12px">平台共享厂商可供所有租户选用，但只有平台管理员可以修改。</n-alert>
      <n-form label-placement="top">
        <n-form-item label="显示名称" required><n-input v-model:value="form.name" :disabled="isSharedReadOnly" placeholder="例如 OpenAI" /></n-form-item>
        <n-form-item label="厂商编码" required><n-input v-model:value="form.code" :disabled="!!editing || isSharedReadOnly" placeholder="例如 openai" /><template #feedback>编码创建后不可修改，用于稳定引用。</template></n-form-item>
        <n-form-item label="来源范围"><n-select v-model:value="form.scopeType" :disabled="!!editing || isSharedReadOnly || !auth.isPlatformAdmin" :options="[{label:'平台共享',value:'PLATFORM_SHARED'},{label:'租户私有',value:'TENANT_PRIVATE'}]" /></n-form-item>
        <n-form-item v-if="auth.isPlatformAdmin && form.scopeType==='TENANT_PRIVATE'" label="归属租户" required><n-select v-model:value="form.tenantId" :disabled="!!editing" :options="tenants.map(t=>({label:t.name+' ('+t.tenantCode+')',value:t.id}))" placeholder="选择目标租户" /></n-form-item>
        <n-form-item label="描述"><n-input v-model:value="form.description" :disabled="isSharedReadOnly" type="textarea" :autosize="{minRows:3,maxRows:5}" /></n-form-item>
      </n-form>
      <template #footer><n-button @click="editorOpen=false">取消</n-button><n-button v-if="!isSharedReadOnly" type="primary" :loading="saving" @click="save">保存</n-button></template>
    </n-modal>
  </section>
</template>

<style scoped>
.provider-page { height: 100%; display: grid; grid-template-rows: auto auto auto 1fr auto; gap: 20px; min-height: 0; }
.page-hdr { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.page-hdr-text h1 { margin: 0; font-size: 22px; font-weight: 650; letter-spacing: -0.01em; }
.page-hdr-text p { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { position: relative; display: flex; align-items: center; flex: 1 1 280px; max-width: 360px; }
.search-icon { position: absolute; left: 10px; color: var(--text-muted); pointer-events: none; }
.search-input { width: 100%; height: 34px; padding: 0 32px; font: inherit; font-size: 13.5px; color: var(--text); background: var(--surface); border: 1px solid var(--border); border-radius: 8px; outline: none; transition: border-color .15s ease, box-shadow .15s ease; }
.search-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--focus-ring); }
.search-clear { position: absolute; right: 8px; width: 20px; height: 20px; border: 0; border-radius: 4px; background: transparent; color: var(--text-muted); cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
.chip-group { display: inline-flex; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.chip { padding: 6px 14px; font-size: 13px; background: var(--surface); border: 0; border-right: 1px solid var(--border); color: var(--text-muted); cursor: pointer; }
.chip:last-child { border-right: 0; }
.chip.is-active { background: var(--accent); color: #fff; }
.reset-btn { padding: 6px 12px; font-size: 13px; background: transparent; border: 1px solid var(--border); border-radius: 8px; color: var(--text-muted); cursor: pointer; }
.table-region { min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.table-region :deep(.n-data-table) { flex: 1; min-height: 0; }
.empty { display: flex; align-items: center; justify-content: center; height: 100%; min-height: 240px; }
.page-foot { display: flex; align-items: center; justify-content: space-between; color: var(--text-muted); font-size: 13px; }
.cell-duo { display: flex; flex-direction: column; gap: 2px; }
.cell-primary { font-weight: 600; color: var(--text); line-height: 1.3; }
.cell-meta { font-size: 12px; color: var(--text-muted); }
.chip-shared { display: inline-block; padding: 2px 8px; font-size: 12px; border-radius: 6px; background: var(--surface-elevated); color: var(--text); }
.chip-private { display: inline-block; padding: 2px 8px; font-size: 12px; border-radius: 6px; color: var(--text-muted); border: 1px solid var(--border); }
:deep(.col-row-actions) { padding-right: 12px; }
:deep(.row-kebab) { color: var(--text-muted); transition: color .15s ease, background .15s ease; border-radius: 6px; padding: 4px; }
:deep(.n-data-table-tr:hover .row-kebab), :deep(.row-kebab:hover), :deep(.row-kebab:focus-visible) { color: var(--text); background: var(--surface); }
@media (max-width: 720px) { .search { max-width: none; } .chip-group { flex: 1 1 auto; } }
</style>
