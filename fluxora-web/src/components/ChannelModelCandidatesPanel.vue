<script setup lang="ts">
/**
 * 通道上游候选面板 —— V10 重建版（嵌入 ProviderChannelManagementView 的通道详情抽屉）。
 *
 * 设计原则：
 * - 候选只是「上游模型标识 + 能力声明 + 启停」；
 * - 不再保存 platform_model_id，因为 V10 已彻底移除全局模型目录；
 * - 候选与所属通道的 tenant_id 一致性由后端服务层强制；
 * - 删除候选前后端校验是否仍被「候选映射」引用，被引用时拦截。
 *
 * 与「租户模型管理页」的关系：候选用于在「租户模型 → 候选映射」表中被引用。
 * 此面板只负责候选本体的 CRUD，不参与映射或路由配置。
 */
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NDropdown, NIcon, useDialog, useMessage } from 'naive-ui'
import { MoreHorizontal, Plus } from 'lucide-vue-next'
import StatusDot from '@/components/StatusDot.vue'
import {
  createChannelCandidate, deleteChannelCandidate,
  disableChannelCandidate, enableChannelCandidate,
  listChannelCandidates, updateChannelCandidate,
  type CandidatePayload, type ProviderChannelModelSummary,
} from '@/services/tenantModel'

const props = defineProps<{
  /** 当前抽屉打开的通道 ID */
  channelId: number
  /** 当前用户是否对该通道有写权限（由父级根据租户归属判定） */
  canManage: boolean
}>()

const message = useMessage()
const dialog = useDialog()

const rows = ref<ProviderChannelModelSummary[]>([])
const loading = ref(false)
const editorOpen = ref(false)
const editing = ref<ProviderChannelModelSummary | null>(null)
const saving = ref(false)

const form = reactive<Required<Omit<CandidatePayload, 'upstreamDisplayName'>> & { upstreamDisplayName: string }>({
  upstreamModelId: '',
  upstreamDisplayName: '',
  supportsStreaming: false,
  supportsToolCalling: false,
  supportsVision: false,
  supportsCache: false,
  enabled: true,
})

async function load() {
  loading.value = true
  try {
    rows.value = await listChannelCandidates(props.channelId)
  } catch (e: any) {
    message.error(e.userMessage || '加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

watch(() => props.channelId, () => { void load() })
onMounted(() => { void load() })

function resetForm() {
  Object.assign(form, {
    upstreamModelId: '', upstreamDisplayName: '',
    supportsStreaming: false, supportsToolCalling: false,
    supportsVision: false, supportsCache: false, enabled: true,
  })
}

function openEditor(r?: ProviderChannelModelSummary) {
  if (r) {
    editing.value = r
    Object.assign(form, {
      upstreamModelId: r.upstreamModelId,
      upstreamDisplayName: r.upstreamDisplayName,
      supportsStreaming: r.supportsStreaming,
      supportsToolCalling: r.supportsToolCalling,
      supportsVision: r.supportsVision,
      supportsCache: r.supportsCache,
      enabled: r.status === 'ENABLED',
    })
  } else {
    editing.value = null
    resetForm()
  }
  editorOpen.value = true
}

async function save() {
  // 表单边界：upstreamModelId 必填、长度 ≤ 256；展示名缺省时用上游标识
  const id = form.upstreamModelId.trim()
  if (!id) { message.warning('请填写上游模型标识'); return }
  if (id.length > 256) { message.warning('上游模型标识过长'); return }
  const payload: CandidatePayload = {
    upstreamModelId: id,
    upstreamDisplayName: form.upstreamDisplayName.trim() || id,
    supportsStreaming: form.supportsStreaming,
    supportsToolCalling: form.supportsToolCalling,
    supportsVision: form.supportsVision,
    supportsCache: form.supportsCache,
    enabled: form.enabled,
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateChannelCandidate(props.channelId, editing.value.id, payload)
      message.success('候选已更新')
    } else {
      await createChannelCandidate(props.channelId, payload)
      message.success('候选已创建')
    }
    editorOpen.value = false
    await load()
  } catch (e: any) {
    message.error(e.userMessage || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

async function toggle(r: ProviderChannelModelSummary) {
  try {
    if (r.status === 'ENABLED') await disableChannelCandidate(props.channelId, r.id)
    else await enableChannelCandidate(props.channelId, r.id)
    message.success(r.status === 'ENABLED' ? '已停用' : '已启用')
    await load()
  } catch (e: any) {
    message.error(e.userMessage || '状态更新失败，请稍后重试')
  }
}

function confirmDelete(r: ProviderChannelModelSummary) {
  dialog.warning({
    title: '删除上游候选',
    content: `确定删除候选「${r.upstreamDisplayName || r.upstreamModelId}」吗？若仍被租户模型映射引用将无法删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteChannelCandidate(props.channelId, r.id)
        message.success('已删除')
        await load()
      } catch (e: any) {
        message.error(e.userMessage || '删除失败，请稍后重试')
      }
    },
  })
}

function rowMenuOpts(r: ProviderChannelModelSummary) {
  if (!props.canManage) return [] as { key: string; label: string }[]
  return [
    { key: 'edit', label: '编辑' },
    { key: 'toggle', label: r.status === 'ENABLED' ? '停用' : '启用' },
    { key: 'delete', label: '删除' },
  ]
}

function handleRowMenu(key: string, r: ProviderChannelModelSummary) {
  if (key === 'edit') openEditor(r)
  else if (key === 'toggle') toggle(r)
  else if (key === 'delete') confirmDelete(r)
}

const columns = computed<any>(() => [
  {
    title: '上游标识', key: 'upstreamModelId', minWidth: 180,
    render: (r: ProviderChannelModelSummary) => h('div', { class: 'cell-duo' }, [
      h('div', { class: 'cell-primary' }, r.upstreamDisplayName || r.upstreamModelId),
      h('div', { class: 'cell-meta' }, r.upstreamModelId),
    ]),
  },
  {
    title: '能力', key: 'caps', minWidth: 200,
    render: (r: ProviderChannelModelSummary) => {
      const caps: string[] = []
      if (r.supportsStreaming) caps.push('流式')
      if (r.supportsToolCalling) caps.push('工具')
      if (r.supportsVision) caps.push('视觉')
      if (r.supportsCache) caps.push('缓存')
      return caps.length === 0
        ? h('span', { class: 'cell-meta' }, '—')
        : h('div', { class: 'cap-row' }, caps.map(c => h('span', { class: 'cap-chip' }, c)))
    },
  },
  { title: '来源', key: 'sourceType', width: 80, render: (r: ProviderChannelModelSummary) => r.sourceType === 'SYNCED' ? '同步' : '手工' },
  { title: '状态', key: 'status', width: 90, render: (r: ProviderChannelModelSummary) => h(StatusDot, { status: r.status }) },
  {
    title: '', key: '__row_actions', width: 44, align: 'right', className: 'col-row-actions',
    render: (r: ProviderChannelModelSummary) => {
      const opts = rowMenuOpts(r)
      if (!opts.length) return null
      return h(NDropdown, { trigger: 'click', placement: 'bottom-end', options: opts, onSelect: (k: string) => handleRowMenu(k, r) }, {
        default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作', onClick: (e: MouseEvent) => e.stopPropagation() }, {
          default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }),
        }),
      })
    },
  },
])
</script>

<template>
  <section class="cand-panel">
    <div class="cand-hdr">
      <span class="cand-title">{{ rows.length }} 个上游模型候选</span>
      <n-button v-if="canManage" size="small" type="primary" @click="openEditor()">
        <template #icon><n-icon><Plus /></n-icon></template>新增候选
      </n-button>
    </div>

    <n-data-table
      v-if="rows.length || loading"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :pagination="false"
      :bordered="false"
      :single-line="false"
      size="small"
    />
    <div v-else class="cand-empty">
      <n-empty description="该通道下还没有上游模型候选" size="small">
        <template #extra>
          <n-button v-if="canManage" size="small" type="primary" @click="openEditor()">新增第一个候选</n-button>
        </template>
      </n-empty>
    </div>

    <n-modal v-model:show="editorOpen" preset="card" :title="editing ? '编辑上游候选' : '新增上游候选'" style="max-width:520px">
      <n-form label-placement="top">
        <n-form-item label="上游模型标识" required>
          <n-input v-model:value="form.upstreamModelId" :disabled="!!editing" placeholder="例如 gpt-4o" />
          <template #feedback>用于向上游传递；同通道内未删除唯一</template>
        </n-form-item>
        <n-form-item label="展示名称">
          <n-input v-model:value="form.upstreamDisplayName" placeholder="留空则使用上游标识" />
        </n-form-item>
        <n-form-item label="能力声明">
          <div class="cap-toggle">
            <n-checkbox v-model:checked="form.supportsStreaming">流式</n-checkbox>
            <n-checkbox v-model:checked="form.supportsToolCalling">工具调用</n-checkbox>
            <n-checkbox v-model:checked="form.supportsVision">视觉</n-checkbox>
            <n-checkbox v-model:checked="form.supportsCache">缓存</n-checkbox>
          </div>
          <template #feedback>租户模型启用前需该候选支撑声明的能力</template>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button @click="editorOpen = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="save">保存</n-button>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
.cand-panel { display: flex; flex-direction: column; gap: 10px; }
.cand-hdr { display: flex; justify-content: space-between; align-items: center; }
.cand-title { font-size: 13px; color: var(--text-muted); }
.cand-empty { padding: 16px 0; }
.cell-duo { display: flex; flex-direction: column; gap: 2px; }
.cell-primary { font-weight: 600; color: var(--text); line-height: 1.3; }
.cell-meta { font-size: 12px; color: var(--text-muted); font-family: 'JetBrains Mono', 'Cascadia Code', monospace; }
.cap-row { display: inline-flex; flex-wrap: wrap; gap: 4px; }
.cap-chip { display: inline-block; padding: 1px 8px; font-size: 12px; border-radius: 6px; background: var(--surface-elevated); color: var(--text-muted); }
.cap-toggle { display: flex; gap: 16px; flex-wrap: wrap; }
:deep(.col-row-actions) { padding-right: 8px; }
:deep(.row-kebab) { color: var(--text-muted); border-radius: 6px; padding: 4px; }
:deep(.n-data-table-tr:hover .row-kebab), :deep(.row-kebab:hover), :deep(.row-kebab:focus-visible) { color: var(--text); background: var(--surface); }
</style>
