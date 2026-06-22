<script setup lang="ts">
/**
 * 我的 API Key — 普通租户用户视角。
 *
 * 整体复用 MemberManagementView 的 5 行 Grid 骨架、搜索/chip 工具栏、n-data-table、
 * 管理面板 Modal，但做了以下关键差异：
 *
 *   1. 创建 Key 成功后，完整 plaintext 仅展示一次（由 ApiKeyRevealPanel 弹窗完成）；
 *   2. 列表/详情只显示 keyPrefix（flx_XXXXXXXX...），不显示密钥段任何字节；
 *   3. 管理面板没有「角色」段（API Key 不支持角色）；改成：资料（名称/过期时间）/ 状态 / 危险区。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDropdown, NIcon } from 'naive-ui'
import {
  KeyRound,
  MoreHorizontal,
  Pencil,
  Plus,
  Power,
  Search,
  Trash2,
  X,
} from 'lucide-vue-next'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import ApiKeyRevealPanel from '@/components/ApiKeyRevealPanel.vue'
import {
  createMyApiKey,
  deleteApiKey,
  disableApiKey,
  enableApiKey,
  fetchMyApiKeyStats,
  getApiKey,
  listMyApiKeys,
  updateApiKey,
  type ApiKeyPage,
  type ApiKeyStats,
  type ApiKeySummary,
  type CreatedApiKeyResponse,
} from '@/services/apiKey'

const auth = useAuthStore()
const message = useMessage()
const dialog = useDialog()

// ---------- 列表 ----------
const keys = ref<ApiKeySummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const statusFilter = ref<'' | 'ENABLED' | 'DISABLED' | 'EXPIRED'>('')
const loading = ref(false)

const stats = ref<ApiKeyStats | null>(null)
const statsLoading = ref(false)
async function loadStats() {
  statsLoading.value = true
  try { stats.value = await fetchMyApiKeyStats() } catch { stats.value = null }
  finally { statsLoading.value = false }
}
const metricItems = computed(() => [
  { label: 'Key 总数', value: stats.value?.total ?? null },
  { label: '启用', value: stats.value?.enabled ?? null },
  { label: '已停用', value: stats.value?.disabled ?? null, tone: 'warn' as const },
  { label: '已过期', value: stats.value?.expired ?? null, tone: 'danger' as const },
  { label: '即将到期', value: stats.value?.expiringSoon ?? null, hint: '30 天内', tone: 'warn' as const },
])

async function loadKeys() {
  loading.value = true
  try {
    const r: ApiKeyPage = await listMyApiKeys({
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined,
      page: page.value,
      size: size.value,
    })
    keys.value = r.items
    total.value = r.total
  } catch { message.error('加载 API Key 列表失败，请稍后重试') }
  finally { loading.value = false }
}
function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
  page.value = 1
  loadKeys()
}
watch([keyword, statusFilter], () => { page.value = 1; loadKeys() })
watch(page, () => loadKeys())

async function refreshAfterWrite() {
  await Promise.all([loadKeys(), loadStats()])
}

onMounted(async () => {
  await Promise.all([loadStats(), loadKeys()])
})

// ---------- Modal ----------
type ModalMode = 'hidden' | 'detail' | 'edit' | 'create'
const modalMode = ref<ModalMode>('hidden')
const selectedKey = ref<ApiKeySummary | null>(null)
const formSubmitting = ref(false)
const profileSaving = ref(false)
const statusSaving = ref(false)

const createForm = ref({ name: '', expireAt: null as number | null })
const editForm = ref({ name: '', expireAt: null as number | null })

const createFormRef = ref<FormInst | null>(null)
const editFormRef = ref<FormInst | null>(null)

// Key 创建后的一次性展示
const revealState = ref<{ show: boolean; plaintext: string; keyPrefix: string; name: string }>({
  show: false, plaintext: '', keyPrefix: '', name: '',
})

const formRules: FormRules = {
  name: [
    { required: true, message: '请输入 Key 名称', trigger: ['input', 'blur'] },
    { max: 64, message: '名称最多 64 个字符', trigger: ['input', 'blur'] },
  ],
}

function statusLabel(s: string) {
  return ({ ENABLED: '启用', DISABLED: '停用', EXPIRED: '过期' } as Record<string, string>)[s] || s
}
function timeAgo(iso: string | null | undefined) {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return '—'
  const diff = Date.now() - t
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return iso.slice(0, 10)
}

function openDetail(k: ApiKeySummary) { selectedKey.value = k; modalMode.value = 'detail' }
function openCreate() {
  createForm.value = { name: '', expireAt: null }
  modalMode.value = 'create'
}
function enterEdit() {
  if (!selectedKey.value) return
  editForm.value = {
    name: selectedKey.value.name,
    expireAt: selectedKey.value.expireAt ? new Date(selectedKey.value.expireAt).getTime() : null,
  }
  modalMode.value = 'edit'
}
function closeModal() { modalMode.value = 'hidden'; selectedKey.value = null }
function backToDetail() { modalMode.value = 'detail' }

function statusDot(s: string) {
  return h('span', { class: ['status-row', `status-${s.toLowerCase()}`] }, [
    h('span', { class: 'status-dot' }),
    h('span', null, statusLabel(s)),
  ])
}

const columns = computed<DataTableColumns<ApiKeySummary>>(() => [
  { title: 'Key 名称', key: 'name', minWidth: 180,
    render: (row) => h('div', { class: 'cell-key' }, [
      h('div', { class: 'cell-key-name' }, row.name),
      h('div', { class: 'cell-key-prefix' }, row.keyPrefix + '...'),
    ]),
  },
  { title: '状态', key: 'status', width: 100,
    render: (row) => statusDot(row.status),
  },
  { title: '过期', key: 'expireAt', width: 120,
    render: (row) => h('span', { class: 'cell-muted' }, row.expireAt?.slice(0, 10) || '永不过期'),
  },
  { title: '最后使用', key: 'lastUsedAt', width: 120,
    render: (row) => h('span', { class: 'cell-muted' }, row.lastUsedAt ? timeAgo(row.lastUsedAt) : '从未'),
  },
  { title: '创建', key: 'createdAt', width: 110,
    render: (row) => h('span', { class: 'cell-muted' }, timeAgo(row.createdAt)),
  },
  { title: '', key: '__row_actions', width: 44, align: 'right', className: 'col-row-actions',
    render: (row) => {
      const opts: { key: string; label: string }[] = []
      opts.push({ key: 'manage', label: '管理' })
      opts.push({ key: 'delete', label: '删除' })
      return h('div', [
        h(NDropdown, {
          trigger: 'click', placement: 'bottom-end', options: opts,
          onSelect: (k: string) => handleRowMenu(k, row),
        }, {
          default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作',
            onClick: (e: MouseEvent) => e.stopPropagation() },
          { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) }),
        }),
      ])
    },
  },
])

function rowProps(row: ApiKeySummary) {
  return { style: 'cursor: pointer', onClick: () => openDetail(row) }
}
function rowMenuOptions() {
  const opts: { key: string; label: string }[] = []
  opts.push({ key: 'manage', label: '管理' })
  opts.push({ key: 'delete', label: '删除' })
  return opts
}
function handleRowMenu(key: string, k: ApiKeySummary) {
  if (key === 'manage') { selectedKey.value = k; enterEdit() }
  else if (key === 'delete') { selectedKey.value = k; confirmDelete() }
}

const showPagination = computed(() => total.value > size.value)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const hasFilter = computed(() => !!keyword.value || !!statusFilter.value)

const profileDirty = computed(() => {
  if (!selectedKey.value) return false
  return editForm.value.name !== selectedKey.value.name
      || (editForm.value.expireAt?.valueOf?.() ?? null)
         !== (selectedKey.value.expireAt ? new Date(selectedKey.value.expireAt).getTime() : null)
})

// ---- 写操作 ----

async function handleCreate() {
  try { await createFormRef.value?.validate() } catch { return }
  formSubmitting.value = true
  try {
    const r: CreatedApiKeyResponse = await createMyApiKey({
      name: createForm.value.name.trim(),
      expireAt: createForm.value.expireAt ? new Date(createForm.value.expireAt).toISOString() : null,
    })
    closeModal()
    revealState.value = { show: true, plaintext: r.plaintext, keyPrefix: r.summary.keyPrefix, name: r.summary.name }
    message.success('API Key 已创建')
    await refreshAfterWrite()
  } catch (e: any) { message.error(e?.userMessage || '创建 API Key 失败，请稍后重试') }
  finally { formSubmitting.value = false }
}

function closeReveal() {
  revealState.value = { show: false, plaintext: '', keyPrefix: '', name: '' }
}

async function saveProfile() {
  if (!selectedKey.value) return
  try { await editFormRef.value?.validate() } catch { return }
  profileSaving.value = true
  try {
    const expireAt = editForm.value.expireAt ? new Date(editForm.value.expireAt).toISOString() : null
    const updated = await updateApiKey(selectedKey.value.id, {
      name: editForm.value.name.trim(),
      expireAtAction: editForm.value.expireAt ? 'SET' : 'CLEAR',
      expireAt: expireAt ?? undefined,
    })
    selectedKey.value = updated
    message.success('API Key 已保存')
    await refreshAfterWrite()
  } catch (e: any) { message.error(e?.userMessage || '保存失败，请稍后重试') }
  finally { profileSaving.value = false }
}

async function doEnable() {
  if (!selectedKey.value) return
  statusSaving.value = true
  try {
    selectedKey.value = await enableApiKey(selectedKey.value.id)
    message.success('API Key 已启用')
    await refreshAfterWrite()
  } catch (e: any) { message.error(e?.userMessage || '启用失败，请稍后重试') }
  finally { statusSaving.value = false }
}

async function doDisable() {
  if (!selectedKey.value) return
  statusSaving.value = true
  try {
    selectedKey.value = await disableApiKey(selectedKey.value.id)
    message.success('API Key 已停用')
    await refreshAfterWrite()
  } catch (e: any) { message.error(e?.userMessage || '停用失败，请稍后重试') }
  finally { statusSaving.value = false }
}

async function doDelete() {
  if (!selectedKey.value) return
  try {
    await deleteApiKey(selectedKey.value.id)
    closeModal()
    message.success('API Key 已删除')
    await refreshAfterWrite()
  } catch (e: any) { message.error(e?.userMessage || '删除失败，请稍后重试') }
}

function confirmDisable() {
  if (!selectedKey.value) return
  dialog.warning({
    title: '确认停用',
    content: `停用后该 Key 将无法使用。确定停用？`,
    positiveText: '确认停用',
    negativeText: '取消',
    onPositiveClick: doDisable,
  })
}
function confirmDelete() {
  if (!selectedKey.value) return
  dialog.error({
    title: '确认删除',
    content: `删除后该 Key 不可恢复。确定删除？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: doDelete,
  })
}
</script>

<template>
  <section class="mykey-page">
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>我的 API Key</h1>
        <p>管理你的 API 访问凭证。完整 Key 仅创建时展示一次，请妥善保存。</p>
      </div>
      <n-button type="primary" class="btn-primary-action" @click="openCreate">
        <template #icon><n-icon><Plus /></n-icon></template>新建 Key
      </n-button>
    </header>
    <MetricStrip :items="metricItems" :loading="statsLoading" />
    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索 Key 名称或前缀" aria-label="搜索 Key" />
        <button v-if="keyword" type="button" class="search-clear" aria-label="清除搜索" @click="keyword = ''">
          <n-icon :size="14"><X /></n-icon></button>
      </label>
      <div class="chip-group" role="radiogroup" aria-label="状态筛选">
        <button v-for="opt in [{label:'全部',value:'' as const},{label:'启用',value:'ENABLED' as const},{label:'停用',value:'DISABLED' as const},{label:'过期',value:'EXPIRED' as const}]"
          :key="opt.value" type="button" class="chip" :class="{'is-active':statusFilter===opt.value}" role="radio"
          :aria-checked="statusFilter===opt.value" @click="statusFilter=opt.value">{{ opt.label }}</button>
      </div>
      <button v-if="hasFilter" type="button" class="reset-btn" @click="resetFilters">重置</button>
    </div>
    <div class="table-region">
      <n-data-table v-if="keys.length || loading" :columns="columns" :data="keys" :loading="loading"
        :pagination="false" :bordered="false" :single-line="false" :row-props="rowProps" flex-height
        class="key-table" size="medium" />
      <div v-else class="empty">
        <n-empty :description="hasFilter?'没有符合条件的 Key':'还没有 API Key'">
          <template #icon><n-icon :size="36"><KeyRound /></n-icon></template>
          <template #extra><n-button v-if="!hasFilter" type="primary" @click="openCreate">新建第一个 Key</n-button>
            <n-button v-else @click="resetFilters">清除筛选</n-button></template>
        </n-empty>
      </div>
    </div>
    <footer class="page-foot"><span class="page-foot-summary">共 <strong>{{ total }}</strong> 个 Key</span>
      <n-pagination v-if="showPagination" v-model:page="page" :page-count="pageCount" :page-size="size" size="small" /></footer>

    <!-- 详情 Modal -->
    <n-modal :show="modalMode==='detail'&&!!selectedKey" preset="card" class="key-modal" :bordered="false"
      :segmented="{content:'soft'}" style="width:min(560px,calc(100vw-32px))"
      @update:show="(v:boolean)=>{if(!v)closeModal()}">
      <template v-if="selectedKey" #header>
        <div class="modal-head"><div class="modal-head-text"><h2>{{ selectedKey.name }}</h2><span class="modal-head-code">{{ selectedKey.keyPrefix }}...</span></div></div>
      </template>
      <template v-if="selectedKey">
        <dl class="detail-list">
          <div class="detail-row"><dt>状态</dt><dd><span class="status-row" :class="`status-${selectedKey.status.toLowerCase()}`"><span class="status-dot" />{{ statusLabel(selectedKey.status) }}</span></dd></div>
          <div class="detail-row"><dt>过期</dt><dd>{{ selectedKey.expireAt?.slice(0,10)||'永不过期' }}</dd></div>
          <div class="detail-row"><dt>最后使用</dt><dd>{{ selectedKey.lastUsedAt?.slice(0,10)||'从未使用' }}</dd></div>
          <div class="detail-row"><dt>创建</dt><dd>{{ selectedKey.createdAt?.slice(0,10)||'—' }}</dd></div>
        </dl>
      </template>
      <template #footer>
        <div class="modal-foot"><n-button @click="closeModal">关闭</n-button>
          <n-button type="primary" @click="enterEdit"><template #icon><n-icon><Pencil /></n-icon></template>管理</n-button></div>
      </template>
    </n-modal>

    <!-- 管理面板 -->
    <n-modal :show="modalMode==='edit'&&!!selectedKey" preset="card" class="key-modal key-manage-modal" :bordered="false"
      :segmented="{content:'soft'}" style="width:min(620px,calc(100vw-32px))"
      @update:show="(v:boolean)=>{if(!v)backToDetail()}">
      <template v-if="selectedKey" #header><div class="modal-head"><h2>管理 API Key</h2><span class="modal-head-code">{{ selectedKey.name }} · {{ selectedKey.keyPrefix }}...</span></div></template>
      <div v-if="selectedKey" class="manage-body">
        <section class="manage-section">
          <header class="manage-section-hd"><h3>资料</h3><p>Key 名称与过期时间可修改；原始值不可变更。</p></header>
          <n-form ref="editFormRef" :model="editForm" :rules="formRules" label-placement="top">
            <n-form-item label="Key 名称" path="name"><n-input v-model:value="editForm.name" placeholder="例如 生产环境 / 开发环境" /></n-form-item>
            <n-form-item label="过期时间"><n-date-picker v-model:value="editForm.expireAt" type="datetime" clearable placeholder="永不过期" style="flex:1" /></n-form-item>
          </n-form>
          <div class="section-action"><n-button :disabled="!profileDirty" :loading="profileSaving" type="primary" @click="saveProfile">保存</n-button></div>
        </section>
        <section class="manage-section">
          <header class="manage-section-hd"><h3>状态</h3><p>停用后该 Key 无法被使用；重新启用后即刻恢复。</p></header>
          <div class="status-row-block">
            <span class="status-row" :class="`status-${selectedKey.status.toLowerCase()}`"><span class="status-dot" />当前：{{ statusLabel(selectedKey.status) }}</span>
            <n-button v-if="selectedKey.status==='ENABLED'" :loading="statusSaving" @click="confirmDisable"><template #icon><n-icon><Power /></n-icon></template>停用</n-button>
            <n-button v-else-if="selectedKey.status==='DISABLED'" :loading="statusSaving" type="primary" ghost @click="doEnable"><template #icon><n-icon><Power /></n-icon></template>启用</n-button>
          </div>
        </section>
        <section class="manage-section danger-section">
          <header class="manage-section-hd"><h3>危险操作</h3><p>删除后该 Key 不可恢复。</p></header>
          <div class="section-action"><n-button type="error" ghost @click="confirmDelete"><template #icon><n-icon><Trash2 /></n-icon></template>删除 Key</n-button></div>
        </section>
      </div>
      <template #footer><div class="modal-foot"><n-button @click="backToDetail">返回</n-button><n-button type="primary" @click="closeModal">完成</n-button></div></template>
    </n-modal>

    <!-- 创建 Modal -->
    <n-modal :show="modalMode==='create'" preset="card" class="key-modal" :bordered="false"
      :segmented="{content:'soft'}" style="width:min(560px,calc(100vw-32px))"
      @update:show="(v:boolean)=>{if(!v)closeModal()}">
      <template #header><div class="modal-head"><h2>新建 API Key</h2><span class="modal-head-code">创建后完整 Key 仅展示一次</span></div></template>
      <n-form ref="createFormRef" :model="createForm" :rules="formRules" label-placement="top">
        <n-form-item label="Key 名称" path="name"><n-input v-model:value="createForm.name" placeholder="例如 本地开发 / CI/CD" /></n-form-item>
        <n-form-item label="过期时间"><n-date-picker v-model:value="createForm.expireAt" type="datetime" clearable placeholder="永不过期" style="flex:1" /></n-form-item>
      </n-form>
      <template #footer><div class="modal-foot"><n-button @click="closeModal">取消</n-button><n-button type="primary" :loading="formSubmitting" @click="handleCreate">创建</n-button></div></template>
    </n-modal>

    <!-- 一次性 Key 展示弹窗 -->
    <ApiKeyRevealPanel
      :show="revealState.show"
      :plaintext="revealState.plaintext"
      :key-prefix="revealState.keyPrefix"
      :name="revealState.name"
      @close="closeReveal"
    />
  </section>
</template>

<style scoped>
.mykey-page{height:100%;display:grid;grid-template-rows:auto auto auto 1fr auto;gap:20px;min-height:0}
.page-hdr,.toolbar{display:flex;align-items:center;gap:16px;flex-wrap:wrap}
.page-hdr{justify-content:space-between}
.page-hdr-text h1{margin:0;font-size:22px;font-weight:650;letter-spacing:-.01em}
.page-hdr-text p{margin:4px 0 0;color:var(--text-muted);font-size:13px}
.search{position:relative;display:flex;align-items:center;flex:1 1 280px;max-width:360px}
.search-icon{position:absolute;left:10px;color:var(--text-muted);pointer-events:none}
.search-input{width:100%;height:34px;padding:0 32px 0 32px;font:inherit;font-size:13.5px;color:var(--text);background:var(--surface);border:1px solid var(--border);border-radius:8px;outline:none;transition:border-color .15s ease,box-shadow .15s ease}
.search-input::placeholder{color:var(--text-muted)}
.search-input:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--focus-ring)}
.search-clear{position:absolute;right:8px;display:inline-flex;align-items:center;justify-content:center;width:20px;height:20px;padding:0;border:0;border-radius:4px;background:transparent;color:var(--text-muted);cursor:pointer}
.search-clear:hover{background:var(--surface-elevated);color:var(--text)}
.chip-group{display:inline-flex;padding:3px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:9px}
.chip{appearance:none;border:0;background:transparent;padding:4px 12px;font:inherit;font-size:12.5px;color:var(--text-muted);border-radius:6px;cursor:pointer;transition:color .15s ease,background .15s ease}
.chip:hover{color:var(--text)}
.chip.is-active{background:var(--surface);color:var(--text);box-shadow:0 1px 2px rgba(0,0,0,.06)}
[data-theme="dark"] .chip.is-active{background:#2a2a28;color:var(--text);box-shadow:none}
.reset-btn{appearance:none;border:0;background:transparent;color:var(--text-muted);font:inherit;font-size:13px;cursor:pointer;padding:4px 6px;border-radius:6px}
.reset-btn:hover{color:var(--text);background:var(--surface-elevated)}
.table-region{min-height:0;display:flex;flex-direction:column;background:var(--surface);border:1px solid var(--border);border-radius:10px;overflow:hidden}
.key-table{flex:1;min-height:0}
.empty{flex:1;display:flex;align-items:center;justify-content:center;padding:48px 16px}
:deep(.key-table .n-data-table-th){font-weight:550;font-size:12px;color:var(--text-muted);letter-spacing:.01em;background:transparent}
:deep(.key-table .n-data-table-td){padding-top:12px;padding-bottom:12px;font-size:13.5px}
:deep(.key-table .n-data-table-tr:hover .n-data-table-td){background:var(--surface-elevated)}
:deep(.key-table .col-row-actions){padding-right:12px}
:deep(.key-table .row-kebab){color:var(--text-muted);transition:color .15s ease;border-radius:6px;padding:4px}
:deep(.key-table .n-data-table-tr:hover .row-kebab),
:deep(.key-table .row-kebab:hover){color:var(--text);background:var(--surface)}
.cell-key-name{font-weight:600;color:var(--text);line-height:1.3}
.cell-key-prefix{margin-top:2px;font-family:var(--font-mono),monospace;font-size:12px;color:var(--text-muted);letter-spacing:-.01em}
.cell-muted{color:var(--text-muted);font-size:13px}
.status-row{display:inline-flex;align-items:center;gap:6px;font-size:13px;color:var(--text)}
.status-dot{width:7px;height:7px;border-radius:50%;background:currentColor;flex-shrink:0}
.status-enabled{color:#20a779}
.status-disabled{color:var(--text-muted)}
.status-expired{color:#d98b20}
.page-foot{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.page-foot-summary{font-size:13px;color:var(--text-muted)}
.page-foot-summary strong{color:var(--text);font-weight:600}
:deep(.key-modal){border-radius:14px}
.modal-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;width:100%}
.modal-head h2,.modal-head-text h2{margin:0;font-size:17px;font-weight:650;letter-spacing:-.005em}
.modal-head-code{display:block;margin-top:4px;font-family:var(--font-mono),monospace;font-size:12px;color:var(--text-muted)}
.detail-list{margin:0;display:grid;grid-template-columns:100px 1fr;row-gap:14px;column-gap:16px}
.detail-row{display:contents}
.detail-row dt{font-size:12.5px;color:var(--text-muted);padding-top:1px}
.detail-row dd{margin:0;font-size:13.5px;color:var(--text);word-break:break-word}
.key-manage-modal :deep(.n-card__content){padding-top:8px;padding-bottom:8px}
.manage-body{display:flex;flex-direction:column;gap:4px}
.manage-section{padding:18px 0;border-bottom:1px solid var(--border)}
.manage-section:first-child{padding-top:6px}
.manage-section:last-child{border-bottom:0;padding-bottom:6px}
.manage-section-hd{margin-bottom:12px}
.manage-section-hd h3{margin:0;font-size:14px;font-weight:600;letter-spacing:-.005em;color:var(--text)}
.manage-section-hd p{margin:4px 0 0;font-size:12.5px;color:var(--text-muted)}
.section-action{display:flex;justify-content:flex-end;margin-top:8px}
.status-row-block{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 12px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:8px}
.danger-section{padding-top:22px}
.danger-section .manage-section-hd h3{color:var(--danger)}
.modal-foot{display:flex;justify-content:flex-end;gap:8px}
@media(max-width:720px){.page-hdr{align-items:flex-start}.toolbar{gap:8px}.search{flex:1 1 100%;max-width:none}.detail-list{grid-template-columns:84px 1fr}}
@media(prefers-reduced-motion:reduce){.search-input,.chip,.row-kebab{transition:none}}
</style>