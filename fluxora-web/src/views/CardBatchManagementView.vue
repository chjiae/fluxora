<script setup lang="ts">
/**
 * 卡密批次管理（租户管理员 / 平台管理员视角）。
 *
 * 双入口：
 *   - props.tenantId 指定时（平台嵌套 /console/tenants/:id/cards/manage）：tenant scope
 *   - 未指定（租户管理员 /console/cards/manage）：service 强制使用 currentUser.tenantId
 *
 * 主体：5 行 Grid（页头 / 指标 / 工具栏 / 表格 1fr / 分页）
 * 创建批次：内联 Dialog，多面额组动态添加；创建成功后弹出 RechargeCardRevealPanel
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDropdown, NIcon } from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import { CreditCard, MoreHorizontal, Plus, Search, Trash2, X } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import RechargeCardRevealPanel from '@/components/RechargeCardRevealPanel.vue'
import {
  createBatch, disableBatch, enableBatch, listBatches, listAllBatches,
  type CardBatchSummary, type CreatedBatchResponse, type DenominationGroup,
} from '@/services/card'

const props = defineProps<{ tenantId?: number }>()
const auth = useAuthStore()
const message = useMessage()
const dialog = useDialog()

const isPlatformView = computed(() => props.tenantId != null)
const effectiveTenantId = computed(() => props.tenantId ?? auth.user?.tenantId ?? null)

// ---------- 列表状态 ----------
const batches = ref<CardBatchSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const statusFilter = ref<'' | 'ENABLED' | 'DISABLED'>('')
const loading = ref(false)

async function loadBatches() {
  loading.value = true
  try {
    if (effectiveTenantId.value != null) {
      const r = await listBatches(effectiveTenantId.value, {
        keyword: keyword.value || undefined,
        status: statusFilter.value || undefined,
        page: page.value, size: size.value,
      })
      batches.value = r.items
      total.value = r.total
    } else if (auth.canManageCrossTenantCards) {
      // 平台管理员的「平台卡密」聚合页（暂未提供独立路由，预留）
      const r = await listAllBatches({
        keyword: keyword.value || undefined,
        status: statusFilter.value || undefined,
        page: page.value, size: size.value,
      })
      batches.value = r.items
      total.value = r.total
    }
  } catch { message.error('加载批次列表失败，请稍后重试') }
  finally { loading.value = false }
}

watch([keyword, statusFilter], () => { page.value = 1; loadBatches() })
watch(page, () => loadBatches())
onMounted(() => loadBatches())

// ---------- 指标 ----------
const metricItems = computed(() => {
  const totals = batches.value.reduce((acc, b) => {
    acc.total += b.totalCount
    acc.available += b.availableCount
    acc.used += b.usedCount
    acc.disabled += b.disabledCount
    acc.expired += b.expiredCount
    return acc
  }, { total: 0, available: 0, used: 0, disabled: 0, expired: 0 })
  return [
    { label: '当前页批次', value: batches.value.length },
    { label: '卡密总数', value: totals.total },
    { label: '可用', value: totals.available },
    { label: '已核销', value: totals.used },
    { label: '已停用', value: totals.disabled, tone: 'warn' as const },
    { label: '已过期', value: totals.expired, tone: 'danger' as const },
  ]
})

// ---------- 表格列 ----------
function statusLabel(s: string) { return ({ ENABLED: '启用', DISABLED: '停用' } as Record<string, string>)[s] || s }
function statusDot(s: string) {
  return h('span', { class: ['status-row', `status-${s.toLowerCase()}`] }, [
    h('span', { class: 'status-dot' }),
    h('span', null, statusLabel(s)),
  ])
}
function timeAgo(iso: string | null | undefined) {
  if (!iso) return '—'
  return iso.slice(0, 10)
}

const columns = computed<DataTableColumns<CardBatchSummary>>(() => {
  const cols: DataTableColumns<CardBatchSummary> = [
    { title: '批次', key: 'batchCode', minWidth: 200,
      render: (row) => h('div', { class: 'cell-batch' }, [
        h('div', { class: 'cell-batch-code' }, row.batchCode),
        h('div', { class: 'cell-batch-name' }, row.name || '—'),
      ]),
    },
  ]
  if (isPlatformView.value || auth.canManageCrossTenantCards) {
    cols.push({ title: '所属租户', key: 'tenantName', width: 140,
      render: (row) => h('span', { class: 'cell-muted' }, row.tenantName),
    })
  }
  cols.push(
    { title: '面额', key: 'denomination', width: 110, align: 'right',
      render: (row) => h('span', { class: 'cell-num' }, row.denomination),
    },
    { title: '可用 / 总数', key: 'usage', width: 130, align: 'right',
      render: (row) => h('span', { class: 'cell-num' }, `${row.availableCount} / ${row.totalCount}`),
    },
    { title: '已核销', key: 'usedCount', width: 90, align: 'right',
      render: (row) => h('span', { class: 'cell-num' }, row.usedCount),
    },
    { title: '状态', key: 'status', width: 100, render: (row) => statusDot(row.status) },
    { title: '过期', key: 'expireAt', width: 110,
      render: (row) => h('span', { class: 'cell-muted' }, row.expireAt ? timeAgo(row.expireAt) : '永不过期'),
    },
    { title: '创建', key: 'createdAt', width: 110,
      render: (row) => h('span', { class: 'cell-muted' }, timeAgo(row.createdAt)),
    },
    { title: '', key: '__act', width: 44, align: 'right', className: 'col-row-actions',
      render: (row) => h(NDropdown, {
        trigger: 'click', placement: 'bottom-end',
        options: [
          { key: row.status === 'ENABLED' ? 'disable' : 'enable',
            label: row.status === 'ENABLED' ? '停用批次' : '启用批次' },
        ],
        onSelect: (k: string) => handleAction(k, row),
      }, {
        default: () => h(NButton, { text: true, class: 'row-kebab', 'aria-label': '更多操作',
          onClick: (e: MouseEvent) => e.stopPropagation() },
          { default: () => h(NIcon, { size: 16 }, { default: () => h(MoreHorizontal) }) }),
      }),
    },
  )
  return cols
})

async function handleAction(action: string, b: CardBatchSummary) {
  if (!effectiveTenantId.value && !b.tenantId) return
  const tid = b.tenantId ?? effectiveTenantId.value!
  if (action === 'disable') {
    dialog.warning({
      title: '确认停用批次',
      content: `停用后该批次内所有未核销卡密将无法使用。确定停用？`,
      positiveText: '确认停用',
      negativeText: '取消',
      onPositiveClick: async () => {
        try {
          await disableBatch(tid, b.id)
          message.success('批次已停用')
          await loadBatches()
        } catch (e: any) { message.error(e?.userMessage || '停用失败') }
      },
    })
  } else if (action === 'enable') {
    try {
      await enableBatch(tid, b.id)
      message.success('批次已启用')
      await loadBatches()
    } catch (e: any) { message.error(e?.userMessage || '启用失败') }
  }
}

// ---------- 创建批次 Dialog ----------
const createOpen = ref(false)
const createSubmitting = ref(false)
const createGroups = ref<Array<{ denomination: string; count: string; name: string; expireAt: number | null }>>([
  { denomination: '', count: '', name: '', expireAt: null },
])
const createFormRef = ref<FormInst | null>(null)

const createSummary = computed(() => {
  let totalCards = 0
  let totalValue = 0
  for (const g of createGroups.value) {
    const d = Number(g.denomination)
    const c = Number(g.count)
    if (!Number.isNaN(d) && !Number.isNaN(c) && d > 0 && c > 0) {
      totalCards += c
      totalValue += d * c
    }
  }
  return { totalCards, totalValue, totalGroups: createGroups.value.length }
})

const createRules: FormRules = {}  // 通过手工逻辑校验，避免 FormRules 与数组配合复杂

function addGroup() {
  createGroups.value.push({ denomination: '', count: '', name: '', expireAt: null })
}
function removeGroup(idx: number) {
  if (createGroups.value.length > 1) createGroups.value.splice(idx, 1)
}

function openCreate() {
  createGroups.value = [{ denomination: '', count: '', name: '', expireAt: null }]
  createOpen.value = true
}

// ---------- RevealPanel 状态 ----------
const revealState = ref<{ show: boolean; plaintexts: string[]; batches: any[] }>({
  show: false, plaintexts: [], batches: [],
})

async function handleCreateSubmit() {
  if (!effectiveTenantId.value) {
    message.error('当前账号未关联租户，无法创建批次')
    return
  }
  // 手工校验
  for (let i = 0; i < createGroups.value.length; i++) {
    const g = createGroups.value[i]!
    if (!g.denomination || Number(g.denomination) <= 0) {
      message.error(`第 ${i + 1} 组面额必须为正数`)
      return
    }
    if (!g.count || !/^\d+$/.test(g.count) || Number(g.count) <= 0) {
      message.error(`第 ${i + 1} 组数量必须为正整数`)
      return
    }
  }

  createSubmitting.value = true
  try {
    const groups: DenominationGroup[] = createGroups.value.map(g => ({
      denomination: g.denomination,
      count: Number(g.count),
      name: g.name || undefined,
      expireAt: g.expireAt ? new Date(g.expireAt).toISOString() : null,
    }))
    const r: CreatedBatchResponse = await createBatch(effectiveTenantId.value, { groups })
    createOpen.value = false
    revealState.value = {
      show: true,
      plaintexts: r.plaintexts,
      batches: r.batches.map(b => ({
        batchCode: b.batchCode, name: b.name, denomination: b.denomination, totalCount: b.totalCount,
      })),
    }
    message.success(`已创建 ${r.batches.length} 个批次，共 ${r.plaintexts.length} 张卡密`)
    await loadBatches()
  } catch (e: any) {
    message.error(e?.userMessage || '创建批次失败，请稍后重试')
    // 失败保留输入
  } finally {
    createSubmitting.value = false
  }
}

function closeReveal() {
  revealState.value = { show: false, plaintexts: [], batches: [] }
}

function rowProps(row: CardBatchSummary) {
  // 点行可未来跳到批次详情；本轮暂不实现独立 detail 页面
  return { style: 'cursor: default' }
}

const showPagination = computed(() => total.value > size.value)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const hasFilter = computed(() => !!keyword.value || !!statusFilter.value)
function resetFilters() { keyword.value = ''; statusFilter.value = ''; page.value = 1; loadBatches() }
</script>

<template>
  <section class="card-mgmt-page">
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>卡密管理</h1>
        <p>{{ isPlatformView ? '为指定租户管理卡密批次。' : '为本租户管理卡密批次；完整卡密仅创建时展示一次。' }}</p>
      </div>
      <n-button type="primary" class="btn-primary-action" @click="openCreate">
        <template #icon><n-icon><Plus /></n-icon></template>
        新建批次
      </n-button>
    </header>

    <MetricStrip :items="metricItems" :loading="loading" />

    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索批次编号或名称" />
        <button v-if="keyword" type="button" class="search-clear" @click="keyword = ''">
          <n-icon :size="14"><X /></n-icon>
        </button>
      </label>
      <div class="chip-group" role="radiogroup" aria-label="批次状态筛选">
        <button v-for="opt in [{label:'全部',value:'' as const},{label:'启用',value:'ENABLED' as const},{label:'停用',value:'DISABLED' as const}]"
          :key="opt.value" type="button" class="chip" :class="{'is-active':statusFilter===opt.value}"
          @click="statusFilter=opt.value">{{ opt.label }}</button>
      </div>
      <button v-if="hasFilter" type="button" class="reset-btn" @click="resetFilters">重置</button>
    </div>

    <div class="table-region">
      <n-data-table v-if="batches.length || loading"
        :columns="columns" :data="batches" :loading="loading"
        :pagination="false" :bordered="false" :single-line="false"
        :row-props="rowProps" flex-height class="batch-table" size="medium" />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有匹配的批次' : '还没有卡密批次'">
          <template #icon><n-icon :size="36"><CreditCard /></n-icon></template>
          <template #extra>
            <n-button v-if="!hasFilter" type="primary" @click="openCreate">新建第一个批次</n-button>
            <n-button v-else @click="resetFilters">清除筛选</n-button>
          </template>
        </n-empty>
      </div>
    </div>

    <footer class="page-foot">
      <span class="page-foot-summary">共 <strong>{{ total }}</strong> 个批次</span>
      <n-pagination v-if="showPagination" v-model:page="page" :page-count="pageCount" :page-size="size" size="small" />
    </footer>

    <!-- 创建批次 Dialog -->
    <n-modal :show="createOpen" preset="card" class="card-modal" :bordered="false"
      :segmented="{content:'soft'}" style="width:min(720px,calc(100vw-32px))"
      @update:show="(v:boolean)=>{if(!v)createOpen=false}">
      <template #header>
        <div class="modal-head">
          <h2>批量创建卡密</h2>
          <span class="modal-head-code">支持一次提交多组面额；完整卡密仅生成后展示一次</span>
        </div>
      </template>
      <div class="create-body">
        <div v-for="(g, idx) in createGroups" :key="idx" class="group-row">
          <span class="group-idx">{{ idx + 1 }}</span>
          <div class="group-fields">
            <div class="field">
              <label>面额</label>
              <n-input v-model:value="g.denomination" placeholder="例如 10.0000" />
            </div>
            <div class="field">
              <label>数量</label>
              <n-input v-model:value="g.count" placeholder="例如 100" />
            </div>
            <div class="field">
              <label>批次备注（可选）</label>
              <n-input v-model:value="g.name" placeholder="例如 双 11 活动" />
            </div>
            <div class="field">
              <label>过期时间（可选）</label>
              <n-date-picker v-model:value="g.expireAt" type="datetime" clearable placeholder="永不过期" style="width:100%" />
            </div>
          </div>
          <button v-if="createGroups.length > 1" class="group-remove" @click="removeGroup(idx)" :aria-label="`删除第 ${idx + 1} 组`">
            <n-icon :size="14"><Trash2 /></n-icon>
          </button>
        </div>
        <n-button block dashed @click="addGroup">
          <template #icon><n-icon><Plus /></n-icon></template>
          添加面额组
        </n-button>

        <div class="create-summary">
          <span>共 <strong>{{ createSummary.totalGroups }}</strong> 组 · <strong>{{ createSummary.totalCards }}</strong> 张 · 总面值 <strong>{{ createSummary.totalValue.toLocaleString() }}</strong></span>
        </div>

        <div class="create-warn">
          <strong>提示：</strong>完整卡密仅在本次生成后展示一次。请立即在结果弹窗中导出 TXT/CSV 并妥善保存。
        </div>
      </div>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="createOpen = false">取消</n-button>
          <n-button type="primary" :loading="createSubmitting" @click="handleCreateSubmit">提交创建</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 一次性展示卡密结果 -->
    <RechargeCardRevealPanel
      :show="revealState.show"
      :plaintexts="revealState.plaintexts"
      :batches="revealState.batches"
      @close="closeReveal"
    />
  </section>
</template>

<style scoped>
.card-mgmt-page{height:100%;display:grid;grid-template-rows:auto auto auto 1fr auto;gap:20px;min-height:0}
.page-hdr{display:flex;justify-content:space-between;align-items:flex-end;gap:16px;flex-wrap:wrap}
.page-hdr-text h1{margin:0;font-size:22px;font-weight:650;letter-spacing:-.01em}
.page-hdr-text p{margin:4px 0 0;color:var(--text-muted);font-size:13px}
.toolbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.search{position:relative;display:flex;align-items:center;flex:1 1 280px;max-width:360px}
.search-icon{position:absolute;left:10px;color:var(--text-muted);pointer-events:none}
.search-input{width:100%;height:34px;padding:0 32px;font:inherit;font-size:13.5px;color:var(--text);background:var(--surface);border:1px solid var(--border);border-radius:8px;outline:none}
.search-input:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--focus-ring)}
.search-clear{position:absolute;right:8px;display:inline-flex;align-items:center;justify-content:center;width:20px;height:20px;padding:0;border:0;border-radius:4px;background:transparent;color:var(--text-muted);cursor:pointer}
.chip-group{display:inline-flex;padding:3px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:9px}
.chip{appearance:none;border:0;background:transparent;padding:4px 12px;font:inherit;font-size:12.5px;color:var(--text-muted);border-radius:6px;cursor:pointer}
.chip:hover{color:var(--text)}
.chip.is-active{background:var(--surface);color:var(--text);box-shadow:0 1px 2px rgba(0,0,0,.06)}
[data-theme="dark"] .chip.is-active{background:#2a2a28;box-shadow:none}
.reset-btn{appearance:none;border:0;background:transparent;color:var(--text-muted);font:inherit;font-size:13px;cursor:pointer;padding:4px 6px;border-radius:6px}
.reset-btn:hover{color:var(--text);background:var(--surface-elevated)}
.table-region{min-height:0;display:flex;flex-direction:column;background:var(--surface);border:1px solid var(--border);border-radius:10px;overflow:hidden}
.batch-table{flex:1;min-height:0}
.empty{flex:1;display:flex;align-items:center;justify-content:center;padding:48px 16px}

:deep(.batch-table .n-data-table-th){font-weight:550;font-size:12px;color:var(--text-muted);letter-spacing:.01em;background:transparent}
:deep(.batch-table .n-data-table-td){padding-top:12px;padding-bottom:12px;font-size:13.5px}
:deep(.batch-table .col-row-actions){padding-right:12px}
:deep(.batch-table .row-kebab){color:var(--text-muted);transition:color .15s ease;border-radius:6px;padding:4px}
:deep(.batch-table .row-kebab:hover){color:var(--text);background:var(--surface-elevated)}

.cell-batch-code{font-weight:600;color:var(--text);font-family:var(--font-mono),monospace;font-size:12.5px}
.cell-batch-name{margin-top:2px;font-size:12px;color:var(--text-muted)}
.cell-muted{color:var(--text-muted);font-size:13px}
.cell-num{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;color:var(--text)}

.status-row{display:inline-flex;align-items:center;gap:6px;font-size:13px;color:var(--text)}
.status-dot{width:7px;height:7px;border-radius:50%;background:currentColor;flex-shrink:0}
.status-enabled{color:#20a779}
.status-disabled{color:var(--text-muted)}

.page-foot{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.page-foot-summary{font-size:13px;color:var(--text-muted)}
.page-foot-summary strong{color:var(--text);font-weight:600}

:deep(.card-modal){border-radius:14px}
.modal-head h2{margin:0;font-size:17px;font-weight:650;letter-spacing:-.005em}
.modal-head-code{display:block;margin-top:4px;font-family:var(--font-mono),monospace;font-size:12px;color:var(--text-muted)}

.create-body{display:flex;flex-direction:column;gap:12px}
.group-row{display:flex;gap:12px;align-items:flex-start;padding:14px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:8px}
.group-idx{font-size:12px;color:var(--text-muted);min-width:18px;padding-top:2px;font-variant-numeric:tabular-nums}
.group-fields{flex:1;display:grid;grid-template-columns:1fr 1fr;gap:10px}
.field label{display:block;font-size:12px;color:var(--text-muted);margin-bottom:4px}
.group-remove{
  appearance:none;border:1px solid var(--border);background:transparent;
  color:var(--text-muted);width:28px;height:28px;border-radius:6px;cursor:pointer;
  display:inline-flex;align-items:center;justify-content:center;
}
.group-remove:hover{color:var(--danger);border-color:var(--danger)}

.create-summary{padding:10px 12px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:8px;font-size:13px;color:var(--text-muted)}
.create-summary strong{color:var(--text);font-weight:600;font-variant-numeric:tabular-nums}

.create-warn{padding:10px 12px;font-size:12.5px;color:var(--danger);background:color-mix(in srgb,var(--danger) 8%,transparent);border:1px solid color-mix(in srgb,var(--danger) 25%,var(--border));border-radius:8px}
.create-warn strong{font-weight:600}

.modal-foot{display:flex;justify-content:flex-end;gap:8px}

@media(max-width:720px){
  .group-fields{grid-template-columns:1fr}
}
</style>
