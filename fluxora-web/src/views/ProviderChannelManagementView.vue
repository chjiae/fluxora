<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NDropdown, NIcon, useDialog, useMessage } from 'naive-ui'
import { MoreHorizontal, Plus, Search, X } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import StatusDot from '@/components/StatusDot.vue'
import CredentialManagementPanel from '@/components/CredentialManagementPanel.vue'
import ChannelModelCandidatesPanel from '@/components/ChannelModelCandidatesPanel.vue'
import {
  createProviderChannel, deleteProviderChannel, getProviderChannelStats,
  getProviderChannel, listProviderBaseUrls, listProviderChannels, listProviders,
  setProviderChannelEnabled, updateProviderChannel,
  type ProviderChannelSummary, type ProviderChannelStats, type ProviderSummary, type ProviderBaseUrlSummary, type Protocol,
} from '@/services/upstream'
import { listTenants, type Tenant } from '@/services/tenant'

const auth = useAuthStore()
const dialog = useDialog()
const message = useMessage()

const rows = ref<ProviderChannelSummary[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
const providerFilter = ref<number | null>(null)
const protocolFilter = ref<Protocol | ''>('')
const statusFilter = ref<string>('')
const tenantFilter = ref<number | null>(null)

const stats = ref<ProviderChannelStats | null>(null)
const statsLoading = ref(false)

const providers = ref<ProviderSummary[]>([])
const tenants = ref<Tenant[]>([])

const detailOpen = ref(false)
const detailChannel = ref<ProviderChannelSummary | null>(null)

const editorOpen = ref(false)
const editing = ref<ProviderChannelSummary | null>(null)
const saving = ref(false)
const form = reactive({ tenantId: null as number | null, providerId: null as number | null, providerBaseUrlId: null as number | null, name: '', priority: 100, weight: 100, connectTimeoutMs: 5000, readTimeoutMs: 60000, remark: '' })
const formBaseUrls = ref<ProviderBaseUrlSummary[]>([])

const metricItems = computed(() => [
  { label: '通道总数', value: stats.value?.total ?? null },
  { label: '已启用', value: stats.value?.enabled ?? null },
  { label: '已停用', value: stats.value?.disabled ?? null, tone: 'warn' as const },
])

const hasFilter = computed(() => !!keyword.value || providerFilter.value !== null || protocolFilter.value !== '' || statusFilter.value !== '' || tenantFilter.value !== null)

async function loadList() {
  loading.value = true
  try {
    const r = await listProviderChannels({
      keyword: keyword.value || undefined, providerId: providerFilter.value ?? undefined,
      protocol: protocolFilter.value || undefined,
      enabled: statusFilter.value === '' ? undefined : statusFilter.value === 'enabled',
      tenantId: auth.isPlatformAdmin ? (tenantFilter.value ?? undefined) : undefined, page: page.value, size,
    })
    rows.value = r.items; total.value = r.total
  } catch (e: any) { message.error(e.userMessage || '加载失败，请稍后重试') }
  finally { loading.value = false }
}

async function loadStats() {
  statsLoading.value = true
  try { stats.value = await getProviderChannelStats(auth.isPlatformAdmin ? (tenantFilter.value ?? undefined) : undefined) } catch { /* 静默 */ }
  finally { statsLoading.value = false }
}

async function refreshAfterWrite() { await Promise.all([loadList(), loadStats()]) }
function resetFilters() { keyword.value = ''; providerFilter.value = null; protocolFilter.value = ''; statusFilter.value = ''; tenantFilter.value = null; page.value = 1; void refreshAfterWrite() }

function openCreateEditor() {
  editing.value = null
  Object.assign(form, { tenantId: auth.user?.tenantId ?? null, providerId: null, providerBaseUrlId: null, name: '', priority: 100, weight: 100, connectTimeoutMs: 5000, readTimeoutMs: 60000, remark: '' })
  formBaseUrls.value = []; editorOpen.value = true
}

function openEditEditor(r: ProviderChannelSummary) {
  editing.value = r
  Object.assign(form, { tenantId: r.tenantId, providerId: r.providerId, providerBaseUrlId: r.providerBaseUrlId, name: r.name, priority: r.priority, weight: r.weight, connectTimeoutMs: r.connectTimeoutMs, readTimeoutMs: r.readTimeoutMs, remark: r.remark || '' })
  if (r.providerId) { listProviderBaseUrls(r.providerId).then(l => { formBaseUrls.value = l }).catch(() => {}) }
  editorOpen.value = true
}

watch(() => form.providerId, async (pid) => {
  form.providerBaseUrlId = null
  if (pid) { try { formBaseUrls.value = await listProviderBaseUrls(pid) } catch { formBaseUrls.value = [] } }
  else { formBaseUrls.value = [] }
})

async function save() {
  if (!form.providerBaseUrlId || !form.name.trim()) { message.warning('请填写通道名称并选择接入地址'); return }
  if (auth.isPlatformAdmin && !form.tenantId) { message.warning('请选择目标租户'); return }
  saving.value = true
  try {
    const p = { tenantId: auth.isPlatformAdmin ? form.tenantId : null, providerBaseUrlId: form.providerBaseUrlId, name: form.name.trim(), priority: form.priority, weight: form.weight, connectTimeoutMs: form.connectTimeoutMs, readTimeoutMs: form.readTimeoutMs, remark: form.remark.trim() || undefined }
    if (editing.value) await updateProviderChannel(editing.value.id, p)
    else await createProviderChannel(p)
    message.success(editing.value ? '通道已更新' : '通道已创建'); editorOpen.value = false; await refreshAfterWrite()
  } catch (e: any) { message.error(e.userMessage || '保存失败，请稍后重试') }
  finally { saving.value = false }
}

async function toggle(r: ProviderChannelSummary) {
  const enable = r.status !== 'ENABLED'
  try { await setProviderChannelEnabled(r.id, enable); message.success(enable ? '已启用' : '已停用'); await refreshAfterWrite() }
  catch (e: any) { message.error(e.userMessage || '状态更新失败，请稍后重试') }
}

function confirmDelete(r: ProviderChannelSummary) {
  dialog.warning({
    title: '删除通道', content: `确定删除「${r.name}」吗？仍被凭证引用时无法删除。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => { try { await deleteProviderChannel(r.id); message.success('已删除'); await refreshAfterWrite() } catch (e: any) { message.error(e.userMessage || '删除失败') } },
  })
}

async function openDetail(r: ProviderChannelSummary) {
  try { detailChannel.value = await getProviderChannel(r.id); detailOpen.value = true }
  catch (e: any) { message.error(e.userMessage || '加载详情失败，请稍后重试') }
}

const detailCanManage = computed(() => {
  if (!detailChannel.value) return false
  if (auth.isPlatformAdmin) return true
  return auth.isTenantAdmin && detailChannel.value.tenantId === auth.user?.tenantId
})

// ---- kebab 操作菜单 ----
function canManageChannel(r: ProviderChannelSummary) {
  if (auth.isPlatformAdmin) return true
  return auth.isTenantAdmin && r.tenantId === auth.user?.tenantId
}

function rowMenuOpts(r: ProviderChannelSummary) {
  const m = canManageChannel(r)
  const opts: { key: string; label: string; disabled?: boolean }[] = []
  if (auth.canUpdateUpstream) opts.push({ key: 'edit', label: '编辑', disabled: !m })
  if (auth.canEnableUpstream || auth.canDisableUpstream) opts.push({ key: 'toggle', label: r.status === 'ENABLED' ? '停用' : '启用', disabled: !m })
  if (auth.canDeleteUpstream) opts.push({ key: 'delete', label: '删除', disabled: !m })
  return opts
}

function handleRowMenu(key: string, r: ProviderChannelSummary) {
  if (key === 'edit') openEditEditor(r)
  else if (key === 'toggle') toggle(r)
  else if (key === 'delete') confirmDelete(r)
}

function rowProps(r: ProviderChannelSummary) {
  return { style: 'cursor: pointer', onClick: () => openDetail(r) }
}

// ---- 表格列 ----
const columns = computed<any>(() => [
  { title: '通道', key: 'name', minWidth: 160, render: (r: ProviderChannelSummary) => h('div', { class: 'cell-duo' }, [h('div', { class: 'cell-primary' }, r.name), h('div', { class: 'cell-meta' }, r.normalizedBaseUrl)]) },
  ...(auth.isPlatformAdmin ? [{ title: '所属租户', key: 'tenantName', width: 130, render: (r: ProviderChannelSummary) => r.tenantName || '—' }] : []),
  { title: '厂商', key: 'providerName', width: 130 },
  { title: '协议', key: 'protocol', width: 90 },
  { title: '状态', key: 'status', width: 90, render: (r: ProviderChannelSummary) => h(StatusDot, { status: r.status }) },
  { title: '优先级', key: 'priority', width: 80 },
  { title: '权重', key: 'weight', width: 70 },
  { title: '凭证', key: 'credentialCount', width: 70 },
  {
    title: '', key: '__row_actions', width: 44, align: 'right', className: 'col-row-actions',
    render: (r: ProviderChannelSummary) => {
      const opts = rowMenuOpts(r)
      if (!opts.length) return null
      return h(NDropdown, { trigger: 'click', placement: 'bottom-end', options: opts, onSelect: (k: string) => handleRowMenu(k, r) }, {
        default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作', onClick: (e: MouseEvent) => e.stopPropagation() }, { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) }),
      })
    },
  },
])

onMounted(async () => {
  try {
    const [pr, tr] = await Promise.all([listProviders({ page: 1, size: 200 }), auth.isPlatformAdmin ? listTenants({ page: 1, size: 200 }) : Promise.resolve({ items: [] as Tenant[], total: 0, page: 1, size: 200 })])
    providers.value = pr.items; tenants.value = tr.items
  } catch { /* 忽略 */ }
  await refreshAfterWrite()
})
</script>

<template>
  <section class="channel-page">
    <header class="page-hdr">
      <div class="page-hdr-text"><h1>上游通道</h1><p>管理租户可用的上游通道；点击通道行可查看详情并管理凭证。</p></div>
      <n-button v-if="auth.canCreateUpstream" type="primary" @click="openCreateEditor()">
        <template #icon><n-icon><Plus /></n-icon></template>新增通道
      </n-button>
    </header>

    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索通道名称" @keyup.enter="page = 1; loadList()" />
        <button v-if="keyword" type="button" class="search-clear" @click="keyword = ''; page = 1; loadList()"><n-icon :size="14"><X /></n-icon></button>
      </label>
      <n-select v-model:value="providerFilter" clearable placeholder="厂商" :options="providers.map(p=>({label:p.name,value:p.id}))" style="width:160px" @update:value="page = 1; refreshAfterWrite()" />
      <n-select v-model:value="protocolFilter" clearable placeholder="协议" :options="[{label:'OPENAI',value:'OPENAI'},{label:'ANTHROPIC',value:'ANTHROPIC'}]" style="width:130px" @update:value="page = 1; refreshAfterWrite()" />
      <n-select v-model:value="statusFilter" clearable placeholder="状态" :options="[{label:'启用',value:'enabled'},{label:'停用',value:'disabled'}]" style="width:120px" @update:value="page = 1; refreshAfterWrite()" />
      <n-select v-if="auth.isPlatformAdmin" v-model:value="tenantFilter" clearable placeholder="租户" :options="tenants.map(t=>({label:t.name,value:t.id}))" style="width:160px" @update:value="page = 1; refreshAfterWrite()" />
      <button v-if="hasFilter" type="button" class="reset-btn" @click="resetFilters">重置</button>
    </div>

    <div class="table-region">
      <n-data-table v-if="rows.length || loading" :columns="columns" :data="rows" :loading="loading" :pagination="false" :bordered="false" :single-line="false" :row-props="rowProps" :scroll-x="1100" flex-height size="medium" />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有符合筛选条件的通道' : '还没有上游通道'">
          <template #extra><n-button v-if="!hasFilter && auth.canCreateUpstream" type="primary" @click="openCreateEditor()">新增第一个通道</n-button></template>
        </n-empty>
      </div>
    </div>

    <footer class="page-foot"><span>共 <strong>{{ total }}</strong> 个通道</span><n-pagination v-model:page="page" :item-count="total" :page-size="size" @update:page="loadList" /></footer>

    <n-modal v-model:show="editorOpen" preset="card" :title="editing ? '编辑通道' : '新增通道'" style="max-width:540px">
      <n-form label-placement="top">
        <n-form-item v-if="auth.isPlatformAdmin" label="目标租户" required><n-select v-model:value="form.tenantId" :disabled="!!editing" :options="tenants.map(t=>({label:t.name+' ('+t.tenantCode+')',value:t.id}))" placeholder="选择通道归属租户" /></n-form-item>
        <n-form-item label="上游厂商" required><n-select v-model:value="form.providerId" :disabled="!!editing" :options="providers.map(p=>({label:p.name+(p.scopeType==='PLATFORM_SHARED'?'（共享）':'（私有）'),value:p.id}))" placeholder="选择厂商" /></n-form-item>
        <n-form-item label="接入基础地址" required><n-select v-model:value="form.providerBaseUrlId" :disabled="!form.providerId" :options="formBaseUrls.map(b=>({label:b.protocol+' · '+b.normalizedBaseUrl,value:b.id}))" placeholder="选择接入地址" /><template #feedback>仅可选择共享或当前租户私有厂商的启用地址。</template></n-form-item>
        <n-form-item label="通道名称" required><n-input v-model:value="form.name" placeholder="例如 OpenAI 主通道" /></n-form-item>
        <div style="display:flex;gap:12px"><n-form-item label="优先级" style="flex:1"><n-input-number v-model:value="form.priority" :min="0" :max="100000" /></n-form-item><n-form-item label="权重" style="flex:1"><n-input-number v-model:value="form.weight" :min="1" :max="100000" /></n-form-item></div>
        <div style="display:flex;gap:12px"><n-form-item label="连接超时(ms)" style="flex:1"><n-input-number v-model:value="form.connectTimeoutMs" :min="100" :max="120000" /></n-form-item><n-form-item label="读取超时(ms)" style="flex:1"><n-input-number v-model:value="form.readTimeoutMs" :min="100" :max="600000" /></n-form-item></div>
        <n-form-item label="备注"><n-input v-model:value="form.remark" type="textarea" :autosize="{minRows:2,maxRows:4}" /></n-form-item>
      </n-form>
      <template #footer><n-button @click="editorOpen=false">取消</n-button><n-button type="primary" :loading="saving" @click="save">保存</n-button></template>
    </n-modal>

    <n-drawer v-model:show="detailOpen" :width="640" placement="right">
      <n-drawer-content v-if="detailChannel" :title="detailChannel.name" closable>
        <div class="detail-grid">
          <div class="detail-item"><span>上游厂商</span><strong>{{ detailChannel.providerName }}</strong></div>
          <div class="detail-item"><span>协议</span><strong>{{ detailChannel.protocol }}</strong></div>
          <div class="detail-item"><span>接入地址</span><strong>{{ detailChannel.normalizedBaseUrl }}</strong></div>
          <div class="detail-item"><span>状态</span><StatusDot :status="detailChannel.status" /></div>
          <div class="detail-item"><span>优先级 / 权重</span><strong>{{ detailChannel.priority }} / {{ detailChannel.weight }}</strong></div>
          <div class="detail-item"><span>超时</span><strong>{{ detailChannel.connectTimeoutMs }} / {{ detailChannel.readTimeoutMs }} ms</strong></div>
        </div>
        <n-divider style="margin:16px 0">通道凭证</n-divider>
        <CredentialManagementPanel :channel-id="detailChannel.id" :can-manage="detailCanManage" />
        <n-divider style="margin:16px 0">上游模型候选</n-divider>
        <!-- 候选仅是「上游模型 + 能力 + 启停」纯 CRUD；
             V10 后不再可映射到任何全局模型，候选用于「租户模型 → 候选映射」中被引用 -->
        <ChannelModelCandidatesPanel :channel-id="detailChannel.id" :can-manage="detailCanManage" />
      </n-drawer-content>
    </n-drawer>
  </section>
</template>

<style scoped>
.channel-page { height: 100%; display: grid; grid-template-rows: auto auto auto 1fr auto; gap: 20px; min-height: 0; }
.page-hdr { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.page-hdr-text h1 { margin: 0; font-size: 22px; font-weight: 650; letter-spacing: -0.01em; }
.page-hdr-text p { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { position: relative; display: flex; align-items: center; flex: 1 1 280px; max-width: 320px; }
.search-icon { position: absolute; left: 10px; color: var(--text-muted); pointer-events: none; }
.search-input { width: 100%; height: 34px; padding: 0 32px; font: inherit; font-size: 13.5px; color: var(--text); background: var(--surface); border: 1px solid var(--border); border-radius: 8px; outline: none; transition: border-color .15s, box-shadow .15s; }
.search-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--focus-ring); }
.search-clear { position: absolute; right: 8px; width: 20px; height: 20px; border: 0; border-radius: 4px; background: transparent; color: var(--text-muted); cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
.reset-btn { padding: 6px 12px; font-size: 13px; background: transparent; border: 1px solid var(--border); border-radius: 8px; color: var(--text-muted); cursor: pointer; }
.table-region { min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.table-region :deep(.n-data-table) { flex: 1; min-height: 0; }
.empty { display: flex; align-items: center; justify-content: center; height: 100%; min-height: 240px; }
.page-foot { display: flex; align-items: center; justify-content: space-between; color: var(--text-muted); font-size: 13px; }
.cell-duo { display: flex; flex-direction: column; gap: 2px; }
.cell-primary { font-weight: 600; color: var(--text); line-height: 1.3; }
.cell-meta { font-size: 12px; color: var(--text-muted); }
:deep(.col-row-actions) { padding-right: 12px; }
:deep(.row-kebab) { color: var(--text-muted); transition: color .15s ease, background .15s ease; border-radius: 6px; padding: 4px; }
:deep(.n-data-table-tr:hover .row-kebab), :deep(.row-kebab:hover), :deep(.row-kebab:focus-visible) { color: var(--text); background: var(--surface); }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 20px; }
.detail-item { display: flex; flex-direction: column; gap: 4px; }
.detail-item span { font-size: 12px; color: var(--text-muted); }
.detail-item strong { font-size: 14px; font-weight: 500; }
@media (max-width: 720px) { .search { max-width: none; } .detail-grid { grid-template-columns: 1fr; } }
</style>
