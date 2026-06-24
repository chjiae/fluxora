<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { NButton, NIcon, useDialog, useMessage } from 'naive-ui'
import { Pencil, RefreshCw, Trash2 } from 'lucide-vue-next'
import StatusDot from '@/components/StatusDot.vue'
import CredentialImportDrawer from '@/components/CredentialImportDrawer.vue'
import {
  createCredential, deleteCredential, replaceCredential, setCredentialEnabled,
  updateCredential, listCredentials,
  type CredentialAuthType, type ProviderCredentialSummary,
} from '@/services/upstream'

const props = defineProps<{ channelId: number; canManage: boolean }>()

const message = useMessage()
const dialog = useDialog()

const rows = ref<ProviderCredentialSummary[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const keyword = ref('')

const editorOpen = ref(false)
const editing = ref<ProviderCredentialSummary | null>(null)
const saving = ref(false)
const form = ref<{ name: string; plaintext: string; priority: number; weight: number; remark: string; authType: CredentialAuthType }>({ name: '', plaintext: '', priority: 100, weight: 100, remark: '', authType: 'BEARER' })
const authTypeOptions = [
  { label: 'Bearer Token', value: 'BEARER' },
  { label: 'x-api-key', value: 'X_API_KEY' },
  { label: '无认证', value: 'NONE' },
]

const replaceOpen = ref(false)
const replaceTarget = ref<ProviderCredentialSummary | null>(null)
const replacePlaintext = ref('')
const replacing = ref(false)

const importOpen = ref(false)

async function load() {
  if (!props.channelId) return
  loading.value = true
  try {
    const r = await listCredentials(props.channelId, { keyword: keyword.value || undefined, page: page.value, size: 20 })
    rows.value = r.items; total.value = r.total
  } catch (e: any) { message.error(e.userMessage || '加载凭证失败，请稍后重试') }
  finally { loading.value = false }
}

watch(() => props.channelId, () => { page.value = 1; keyword.value = ''; void load() }, { immediate: true })
watch(() => form.value.authType, authType => { if (authType === 'NONE') form.value.plaintext = '' })

function openCreate() { editing.value = null; form.value = { name: '', plaintext: '', priority: 100, weight: 100, remark: '', authType: 'BEARER' }; editorOpen.value = true }
function openEdit(r: ProviderCredentialSummary) { editing.value = r; form.value = { name: r.name, plaintext: '', priority: r.priority, weight: r.weight, remark: r.remark || '', authType: r.authType }; editorOpen.value = true }
function clearPlaintext() { form.value.plaintext = ''; replacePlaintext.value = '' }

async function save() {
  if (editing.value) {
    if (!form.value.name.trim()) { message.warning('请填写凭证名称'); return }
    saving.value = true
    try {
      await updateCredential(editing.value.id, { name: form.value.name.trim(), priority: form.value.priority, weight: form.value.weight, remark: form.value.remark.trim() || undefined, authType: form.value.authType })
      message.success('凭证已更新'); editorOpen.value = false; clearPlaintext(); await load()
    } catch (e: any) { message.error(e.userMessage || '保存失败') }
    finally { saving.value = false }
  } else {
    if (form.value.authType !== 'NONE' && !form.value.plaintext) { message.warning('请输入上游访问凭证'); return }
    saving.value = true
    try {
      await createCredential({ providerChannelId: props.channelId, plaintext: form.value.plaintext || undefined, name: form.value.name.trim() || undefined, priority: form.value.priority, weight: form.value.weight, remark: form.value.remark.trim() || undefined, authType: form.value.authType })
      message.success('凭证已创建'); editorOpen.value = false; clearPlaintext(); await load()
    } catch (e: any) { message.error(e.userMessage || '创建失败') }
    finally { saving.value = false }
  }
}

function openReplace(r: ProviderCredentialSummary) { replaceTarget.value = r; replacePlaintext.value = ''; replaceOpen.value = true }

async function doReplace() {
  if (!replaceTarget.value || !replacePlaintext.value) { message.warning('请输入新凭证'); return }
  const target = replaceTarget.value; const plaintext = replacePlaintext.value
  dialog.warning({
    title: '替换凭证', content: '替换后将使用新密文，旧密文不再被引用。确定继续吗？', positiveText: '确认替换', negativeText: '取消',
    onPositiveClick: async () => {
      replacing.value = true
      try { await replaceCredential(target.id, plaintext); message.success('凭证已替换'); replaceOpen.value = false; clearPlaintext(); await load() }
      catch (e: any) { message.error(e.userMessage || '替换失败，请检查输入后重试') }
      finally { replacing.value = false }
    },
  })
}

async function toggle(r: ProviderCredentialSummary) {
  const enable = r.status !== 'ENABLED'
  try { await setCredentialEnabled(r.id, enable); message.success(enable ? '已启用' : '已停用'); await load() }
  catch (e: any) { message.error(e.userMessage || '状态更新失败') }
}

function confirmDelete(r: ProviderCredentialSummary) {
  dialog.warning({
    title: '删除凭证', content: `确定删除「${r.name}」吗？删除后该凭证视为不存在，可重新导入。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => { try { await deleteCredential(r.id); message.success('已删除'); await load() } catch (e: any) { message.error(e.userMessage || '删除失败') } },
  })
}

const columns = computed(() => [
  { title: '名称', key: 'name', minWidth: 150 },
  { title: '脱敏值', key: 'maskedValue', width: 140 },
  { title: '认证', key: 'authType', width: 100 },
  { title: '状态', key: 'status', width: 90, render: (r: ProviderCredentialSummary) => h(StatusDot, { status: r.status }) },
  { title: '优先级', key: 'priority', width: 80 },
  { title: '权重', key: 'weight', width: 80 },
  { title: '备注', key: 'remark', minWidth: 140, ellipsis: { tooltip: true }, render: (r: ProviderCredentialSummary) => r.remark || '—' },
  {
    title: '操作', key: 'actions', fixed: 'right' as const, width: 200, render: (r: ProviderCredentialSummary) => h('div', { class: 'row-actions' }, [
      h(NButton, { text: true, size: 'tiny', disabled: !props.canManage, onClick: () => openEdit(r) }, { default: () => h(NIcon, { size: 14 }, { default: () => h(Pencil) }), icon: () => h(NIcon, { size: 14 }, { default: () => h(Pencil) }) }),
      h(NButton, { text: true, size: 'tiny', disabled: !props.canManage, onClick: () => openReplace(r) }, { default: () => '替换' }),
      h(NButton, { text: true, size: 'tiny', disabled: !props.canManage, onClick: () => toggle(r) }, { default: () => r.status === 'ENABLED' ? '停用' : '启用' }),
      h(NButton, { text: true, size: 'tiny', disabled: !props.canManage, class: 'danger-text', onClick: () => confirmDelete(r) }, { default: () => h('span', { class: 'danger-text' }, '删除') }),
    ]),
  },
])
</script>

<template>
  <div class="credential-panel">
    <div class="panel-toolbar">
      <n-input v-model:value="keyword" placeholder="搜索凭证名称" clearable size="small" style="max-width:240px" @keyup.enter="page = 1; load()" />
      <div style="flex:1" />
      <n-button size="small" :disabled="!canManage" @click="importOpen = true">批量导入</n-button>
      <n-button size="small" type="primary" :disabled="!canManage" @click="openCreate">新增凭证</n-button>
    </div>

    <n-data-table :columns="columns" :data="rows" :loading="loading" :pagination="false" :bordered="false" :single-line="false" :scroll-x="900" size="small" />
    <div class="panel-foot"><span>共 {{ total }} 条凭证</span><n-pagination v-model:page="page" :item-count="total" :page-size="20" size="small" @update:page="load" /></div>

    <n-modal v-model:show="editorOpen" preset="card" :title="editing ? '编辑凭证' : '新增凭证'" style="max-width:480px">
      <n-form label-placement="top">
        <n-form-item label="凭证名称"><n-input v-model:value="form.name" placeholder="留空将自动生成" /></n-form-item>
        <n-form-item label="认证方式"><n-select v-model:value="form.authType" :options="authTypeOptions" /></n-form-item>
        <n-form-item v-if="!editing && form.authType !== 'NONE'" label="上游凭证" required><n-input v-model:value="form.plaintext" type="password" show-password-on="click" placeholder="输入上游访问凭证" /><template #feedback>创建后不可查看明文，仅保留脱敏标识。</template></n-form-item>
        <n-alert v-else-if="!editing && form.authType === 'NONE'" type="info" :bordered="false" style="margin-bottom:8px">当前凭证不会向上游注入认证头。</n-alert>
        <n-alert v-else type="info" :bordered="false" style="margin-bottom:8px">编辑元数据不会覆盖已有密文；如需更换明文请使用「替换」。</n-alert>
        <div style="display:flex;gap:12px"><n-form-item label="优先级" style="flex:1"><n-input-number v-model:value="form.priority" :min="0" :max="100000" /></n-form-item><n-form-item label="权重" style="flex:1"><n-input-number v-model:value="form.weight" :min="1" :max="100000" /></n-form-item></div>
        <n-form-item label="备注"><n-input v-model:value="form.remark" /></n-form-item>
      </n-form>
      <template #footer><n-button @click="editorOpen = false; clearPlaintext()">取消</n-button><n-button type="primary" :loading="saving" @click="save">保存</n-button></template>
    </n-modal>

    <n-modal v-model:show="replaceOpen" preset="card" title="替换凭证" style="max-width:440px">
      <n-alert type="warning" :bordered="false" style="margin-bottom:12px">替换将写入新密文与指纹，旧密文不再被引用；此操作不可撤销。</n-alert>
      <n-form label-placement="top"><n-form-item label="新凭证" required><n-input v-model:value="replacePlaintext" type="password" show-password-on="click" placeholder="输入新的上游凭证" /></n-form-item></n-form>
      <template #footer><n-button @click="replaceOpen = false; clearPlaintext()">取消</n-button><n-button type="warning" :loading="replacing" @click="doReplace">确认替换</n-button></template>
    </n-modal>

    <CredentialImportDrawer v-model:show="importOpen" :channel-id="channelId" @done="load" />
  </div>
</template>

<style scoped>
.credential-panel { display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.panel-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.panel-foot { display: flex; align-items: center; justify-content: space-between; color: var(--text-muted); font-size: 12px; }
.row-actions { display: flex; gap: 6px; align-items: center; }
:deep(.danger-text) { color: var(--danger); }
</style>
