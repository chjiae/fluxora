<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NDropdown, NIcon, useDialog, useMessage } from 'naive-ui'
import { MoreHorizontal, Plus } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import StatusDot from '@/components/StatusDot.vue'
import {
  createProviderBaseUrl, deleteProviderBaseUrl, getProvider, getProviderBaseUrlStats,
  listProviderBaseUrls, listProviders, setProviderBaseUrlEnabled, updateProviderBaseUrl,
  type ProviderBaseUrlSummary, type ProviderBaseUrlStats, type ProviderSummary, type Protocol,
} from '@/services/upstream'

const auth = useAuthStore()
const dialog = useDialog()
const message = useMessage()

const providers = ref<ProviderSummary[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedProvider = ref<ProviderSummary | null>(null)
const rows = ref<ProviderBaseUrlSummary[]>([])
const stats = ref<ProviderBaseUrlStats | null>(null)
const loading = ref(false)
const statsLoading = ref(false)

const editorOpen = ref(false)
const editing = ref<ProviderBaseUrlSummary | null>(null)
const saving = ref(false)
const form = reactive({ protocol: 'OPENAI' as Protocol, baseUrl: '', displayName: '', remark: '' })

const metricItems = computed(() => [
  { label: '地址总数', value: stats.value?.total ?? null },
  { label: 'OPENAI', value: stats.value?.openai ?? null },
  { label: 'ANTHROPIC', value: stats.value?.anthropic ?? null },
  { label: '已停用', value: stats.value?.disabled ?? null, tone: 'warn' as const },
])

const canManage = computed(() => {
  if (!selectedProvider.value) return false
  if (selectedProvider.value.scopeType === 'PLATFORM_SHARED') return auth.isPlatformAdmin
  return auth.isPlatformAdmin || (auth.isTenantAdmin && selectedProvider.value.tenantId === auth.user?.tenantId)
})

async function loadProviders() {
  try {
    const r = await listProviders({ page: 1, size: 200 })
    providers.value = r.items
    if (r.items.length && !selectedProviderId.value && r.items[0]) selectedProviderId.value = r.items[0].id
  } catch { /* 忽略 */ }
}

async function loadAll() {
  if (!selectedProviderId.value) return
  loading.value = true; statsLoading.value = true
  try {
    const [provider, list, s] = await Promise.all([getProvider(selectedProviderId.value), listProviderBaseUrls(selectedProviderId.value), getProviderBaseUrlStats(selectedProviderId.value)])
    selectedProvider.value = provider; rows.value = list; stats.value = s
  } catch (e: any) { message.error(e.userMessage || '加载失败，请稍后重试') }
  finally { loading.value = false; statsLoading.value = false }
}

async function refreshAfterWrite() { await loadAll() }
watch(selectedProviderId, () => { void loadAll() })

function openEditor(r?: ProviderBaseUrlSummary) {
  if (r) { editing.value = r; Object.assign(form, { protocol: r.protocol, baseUrl: r.originalBaseUrl, displayName: r.displayName || '', remark: r.remark || '' }) }
  else { editing.value = null; Object.assign(form, { protocol: 'OPENAI', baseUrl: '', displayName: '', remark: '' }) }
  editorOpen.value = true
}

async function save() {
  if (!form.baseUrl.trim() || !selectedProviderId.value) { message.warning('请填写接入基础 URL'); return }
  saving.value = true
  try {
    const p = { providerId: selectedProviderId.value, protocol: form.protocol, baseUrl: form.baseUrl.trim(), displayName: form.displayName.trim() || undefined, remark: form.remark.trim() || undefined }
    if (editing.value) await updateProviderBaseUrl(editing.value.id, p)
    else await createProviderBaseUrl(p)
    message.success(editing.value ? '地址已更新' : '地址已创建'); editorOpen.value = false; await refreshAfterWrite()
  } catch (e: any) { message.error(e.userMessage || '保存失败，请稍后重试') }
  finally { saving.value = false }
}

async function toggle(r: ProviderBaseUrlSummary) {
  const enable = r.status !== 'ENABLED'
  try { await setProviderBaseUrlEnabled(r.id, enable); message.success(enable ? '已启用' : '已停用'); await refreshAfterWrite() }
  catch (e: any) { message.error(e.userMessage || '状态更新失败，请稍后重试') }
}

function confirmDelete(r: ProviderBaseUrlSummary) {
  dialog.warning({
    title: '删除接入地址', content: `确定删除「${r.normalizedBaseUrl}」吗？仍被通道引用时无法删除。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => { try { await deleteProviderBaseUrl(r.id); message.success('已删除'); await refreshAfterWrite() } catch (e: any) { message.error(e.userMessage || '删除失败') } },
  })
}

function rowMenuOpts(r: ProviderBaseUrlSummary) {
  const m = canManage.value
  const opts: { key: string; label: string; disabled?: boolean }[] = []
  if (auth.canUpdateUpstream) opts.push({ key: 'edit', label: '编辑', disabled: !m })
  if (auth.canEnableUpstream || auth.canDisableUpstream) opts.push({ key: 'toggle', label: r.status === 'ENABLED' ? '停用' : '启用', disabled: !m })
  if (auth.canDeleteUpstream) opts.push({ key: 'delete', label: '删除', disabled: !m })
  return opts
}
function handleRowMenu(key: string, r: ProviderBaseUrlSummary) {
  if (key === 'edit') openEditor(r)
  else if (key === 'toggle') toggle(r)
  else if (key === 'delete') confirmDelete(r)
}

function rowProps(r: ProviderBaseUrlSummary) {
  return { style: 'cursor: pointer', onClick: () => openEditor(r) }
}

const columns = computed<any>(() => [
  { title: '协议', key: 'protocol', width: 110 },
  { title: '接入基础 URL', key: 'normalizedBaseUrl', minWidth: 280, render: (r: ProviderBaseUrlSummary) => h('div', { class: 'cell-duo' }, [h('div', { class: 'cell-primary' }, r.normalizedBaseUrl), h('div', { class: 'cell-meta' }, r.originalBaseUrl)]) },
  { title: '显示名称', key: 'displayName', width: 140, render: (r: ProviderBaseUrlSummary) => r.displayName || '—' },
  { title: '状态', key: 'status', width: 100, render: (r: ProviderBaseUrlSummary) => h(StatusDot, { status: r.status }) },
  {
    title: '', key: '__row_actions', width: 44, align: 'right', className: 'col-row-actions',
    render: (r: ProviderBaseUrlSummary) => {
      const opts = rowMenuOpts(r)
      if (!opts.length) return null
      return h(NDropdown, { trigger: 'click', placement: 'bottom-end', options: opts, onSelect: (k: string) => handleRowMenu(k, r) }, {
        default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作', onClick: (e: MouseEvent) => e.stopPropagation() }, { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) }),
      })
    },
  },
])

onMounted(async () => { await loadProviders(); await loadAll() })
</script>

<template>
  <section class="baseurl-page">
    <header class="page-hdr">
      <div class="page-hdr-text"><h1>接入地址</h1><p>管理厂商在不同协议下的逻辑接入基础地址；同一物理 URL 可按不同协议分别创建。</p></div>
      <div class="hdr-actions">
        <n-select v-model:value="selectedProviderId" :options="providers.map(p => ({ label: p.name + ' (' + p.code + ')', value: p.id }))" placeholder="选择厂商" style="width:260px" />
        <n-button v-if="canManage && auth.canCreateUpstream" type="primary" :disabled="!selectedProviderId" @click="openEditor()">
          <template #icon><n-icon><Plus /></n-icon></template>新增地址
        </n-button>
      </div>
    </header>

    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <n-alert v-if="selectedProvider && selectedProvider.scopeType === 'PLATFORM_SHARED' && !auth.isPlatformAdmin" type="info" :bordered="false">平台共享接入地址可供创建通道，但只有平台管理员可以修改。</n-alert>

    <div class="table-region">
      <n-data-table v-if="rows.length || loading" :columns="columns" :data="rows" :loading="loading" :pagination="false" :bordered="false" :single-line="false" :row-props="rowProps" :scroll-x="800" flex-height size="medium" />
      <div v-else class="empty"><n-empty :description="selectedProviderId ? '该厂商还没有接入地址' : '请先选择厂商'" /></div>
    </div>

    <n-modal v-model:show="editorOpen" preset="card" :title="editing ? '编辑接入地址' : '新增接入地址'" style="max-width:520px">
      <n-form label-placement="top">
        <n-form-item label="协议" required><n-select v-model:value="form.protocol" :options="[{label:'OPENAI',value:'OPENAI'},{label:'ANTHROPIC',value:'ANTHROPIC'}]" /><template #feedback>一个接入基础地址只对应一个协议；同一 URL 可在不同协议下分别创建。</template></n-form-item>
        <n-form-item label="接入基础 URL" required><n-input v-model:value="form.baseUrl" placeholder="https://api.example.com/v1" /><template #feedback>请输入完整基础地址，不要填写 /chat/completions 等具体接口路径。</template></n-form-item>
        <n-form-item label="显示名称"><n-input v-model:value="form.displayName" placeholder="可选" /></n-form-item>
        <n-form-item label="备注"><n-input v-model:value="form.remark" type="textarea" :autosize="{minRows:2,maxRows:4}" /></n-form-item>
      </n-form>
      <template #footer><n-button @click="editorOpen=false">取消</n-button><n-button type="primary" :loading="saving" @click="save">保存</n-button></template>
    </n-modal>
  </section>
</template>

<style scoped>
.baseurl-page { height: 100%; display: grid; grid-template-rows: auto auto auto 1fr; gap: 20px; min-height: 0; }
.page-hdr { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.page-hdr-text h1 { margin: 0; font-size: 22px; font-weight: 650; letter-spacing: -0.01em; }
.page-hdr-text p { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }
.hdr-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.table-region { min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.table-region :deep(.n-data-table) { flex: 1; min-height: 0; }
.empty { display: flex; align-items: center; justify-content: center; height: 100%; min-height: 240px; }
.cell-duo { display: flex; flex-direction: column; gap: 2px; }
.cell-primary { font-weight: 600; color: var(--text); line-height: 1.3; }
.cell-meta { font-size: 12px; color: var(--text-muted); }
:deep(.col-row-actions) { padding-right: 12px; }
:deep(.row-kebab) { color: var(--text-muted); transition: color .15s ease, background .15s ease; border-radius: 6px; padding: 4px; }
:deep(.n-data-table-tr:hover .row-kebab), :deep(.row-kebab:hover), :deep(.row-kebab:focus-visible) { color: var(--text); background: var(--surface); }
</style>
