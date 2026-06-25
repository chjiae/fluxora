<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  confirmRelease,
  confirmSettle,
  listPendingReconciliations,
  type BillingReservationView,
  type ReconciliationActionRequest,
} from '@/services/reconciliation'

const message = useMessage()
const rows = ref<BillingReservationView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const tenantId = ref<number | undefined>()
const loading = ref(false)

const actionOpen = ref(false)
const actionType = ref<'release' | 'settle'>('release')
const actionTarget = ref<BillingReservationView | null>(null)
const actionForm = ref<{ finalAmount: string; reason: string }>({ finalAmount: '', reason: '' })
const actionFormRef = ref<FormInst | null>(null)
const submitting = ref(false)

const rules: FormRules = {
  finalAmount: [{
    validator: (_r, v) => {
      if (actionType.value === 'release') return true
      if (!v) return new Error('请输入确认结算金额')
      const n = Number(v)
      if (Number.isNaN(n) || n < 0) return new Error('结算金额不能为负数')
      return true
    },
    trigger: ['input', 'blur'],
  }],
  reason: [
    { required: true, message: '请填写对账原因', trigger: ['input', 'blur'] },
    { max: 256, message: '原因最多 256 字符', trigger: ['input', 'blur'] },
  ],
}

async function load() {
  loading.value = true
  try {
    const r = await listPendingReconciliations({ tenantId: tenantId.value, page: page.value, size: size.value })
    rows.value = r.items
    total.value = r.total
  } catch (e: any) {
    message.error(e?.userMessage || '加载待对账记录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

watch([tenantId, page], () => load())
onMounted(load)

const metricItems = computed(() => [
  { label: '待对账记录', value: total.value },
  { label: '当前页冻结金额', value: sum(rows.value.map(x => x.reservationAmount)) },
  { label: '当前页待确认实扣', value: sum(rows.value.map(x => x.actualAmount)) },
  { label: '当前页超额风险', value: sum(rows.value.map(x => x.outstandingAmount)), tone: 'warn' as const },
])

function sum(values: Array<string | null>) {
  const n = values.reduce((acc, v) => acc + (v ? Number(v) : 0), 0)
  return n.toFixed(8)
}
function time(v: string | null | undefined) { return v ? v.slice(0, 16).replace('T', ' ') : '—' }
function status(v: string) {
  return ({ RECONCILIATION_PENDING: '待对账', RESERVED: '已冻结', SETTLED: '已结算', RELEASED: '已释放' } as Record<string, string>)[v] || v
}
function openAction(row: BillingReservationView, type: 'release' | 'settle') {
  actionType.value = type
  actionTarget.value = row
  actionForm.value = {
    finalAmount: type === 'settle' ? (row.actualAmount ?? row.reservationAmount) : '',
    reason: '',
  }
  actionOpen.value = true
}
function closeAction() {
  actionOpen.value = false
  actionTarget.value = null
}
async function submitAction() {
  if (!actionTarget.value) return
  try { await actionFormRef.value?.validate() } catch { return }
  const payload: ReconciliationActionRequest = { reason: actionForm.value.reason.trim() }
  if (actionType.value === 'settle') payload.finalAmount = actionForm.value.finalAmount
  submitting.value = true
  try {
    if (actionType.value === 'release') {
      await confirmRelease(actionTarget.value.requestId, payload)
      message.success('已确认释放冻结金额')
    } else {
      await confirmSettle(actionTarget.value.requestId, payload)
      message.success('已确认结算')
    }
    closeAction()
    await load()
  } catch (e: any) {
    message.error(e?.userMessage || '对账操作失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

const columns = computed<DataTableColumns<BillingReservationView>>(() => [
  { title: '请求时间', key: 'createdAt', width: 150, render: row => time(row.createdAt) },
  { title: '请求 ID', key: 'requestId', minWidth: 220, render: row => h('span', { class: 'mono' }, row.requestId) },
  { title: '租户 / 用户', key: 'tenantUser', width: 130, render: row => `${row.tenantId} / ${row.userId}` },
  { title: '模型', key: 'tenantModelCode', width: 130 },
  { title: '状态', key: 'status', width: 90, render: row => h('span', { class: 'badge warn' }, status(row.status)) },
  { title: '投递', key: 'upstreamDispatchState', width: 120, render: row => row.upstreamDispatchState || '—' },
  { title: '冻结金额', key: 'reservationAmount', width: 120, align: 'right', render: row => h('span', { class: 'mono' }, row.reservationAmount) },
  { title: '实际金额', key: 'actualAmount', width: 120, align: 'right', render: row => h('span', { class: 'mono' }, row.actualAmount ?? '待确认') },
  { title: '待补差额', key: 'outstandingAmount', width: 120, align: 'right', render: row => h('span', { class: 'mono' }, row.outstandingAmount) },
  { title: '原因', key: 'reasonCode', minWidth: 150, render: row => h('span', { class: 'muted' }, row.reasonCode || '—') },
  { title: '操作', key: '__actions', width: 170, fixed: 'right',
    render: row => h('div', { class: 'actions' }, [
      h(NButton, { size: 'small', onClick: () => openAction(row, 'release') }, { default: () => '释放' }),
      h(NButton, { size: 'small', type: 'primary', ghost: true, onClick: () => openAction(row, 'settle') }, { default: () => '结算' }),
    ]),
  },
])

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
</script>

<template>
  <section class="reconcile-page">
    <header class="page-hdr">
      <div>
        <h1>余额对账</h1>
        <p>处理未知投递、用量缺失、超出预冻结金额或超时未闭环的请求。人工动作会写入审计流水。</p>
      </div>
    </header>

    <MetricStrip :items="metricItems" :loading="loading" />

    <div class="toolbar">
      <n-input-number v-model:value="tenantId" clearable :show-button="false" placeholder="按租户 ID 过滤" style="width:180px" />
      <n-button :loading="loading" @click="load">刷新</n-button>
    </div>

    <div class="table">
      <n-data-table :columns="columns" :data="rows" :loading="loading" :pagination="false" :bordered="false" :single-line="false" />
      <n-empty v-if="!loading && !rows.length" description="暂无待对账记录" />
    </div>

    <footer>
      <span>共 {{ total }} 条待对账记录</span>
      <n-pagination v-if="total > size" v-model:page="page" :page-count="pageCount" :page-size="size" />
    </footer>

    <n-modal v-model:show="actionOpen" preset="card" style="width:min(520px,calc(100vw - 32px))" :bordered="false">
      <template #header>
        {{ actionType === 'release' ? '确认释放冻结金额' : '确认最终结算' }}
      </template>
      <n-form ref="actionFormRef" :model="actionForm" :rules="rules" label-placement="top">
        <n-form-item v-if="actionType === 'settle'" label="最终结算金额" path="finalAmount">
          <n-input v-model:value="actionForm.finalAmount" placeholder="不得超过预冻结金额" />
        </n-form-item>
        <n-form-item label="对账原因" path="reason">
          <n-input v-model:value="actionForm.reason" type="textarea" :autosize="{minRows:2,maxRows:4}" placeholder="例如 上游未返回用量，确认释放；或人工核验用量后确认结算" />
        </n-form-item>
      </n-form>
      <p class="hint">不会自动追扣超出预冻结的金额；超额请求需保留待对账并由线下流程处理。</p>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="closeAction">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="submitAction">确认</n-button>
        </div>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
.reconcile-page{height:100%;display:grid;grid-template-rows:auto auto auto 1fr auto;gap:20px;min-height:0}
.page-hdr h1{margin:0;font-size:22px;font-weight:650}
.page-hdr p{margin:5px 0 0;color:var(--text-muted);font-size:13px}
.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.table{min-height:0;background:var(--surface);border:1px solid var(--border);border-radius:10px;overflow:hidden;display:flex;flex-direction:column}
.table :deep(.n-data-table){flex:1;min-height:0}
.mono{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums}
.muted,.hint,footer{color:var(--text-muted);font-size:13px}
.badge{display:inline-flex;padding:2px 7px;border-radius:4px;font-size:12px}
.badge.warn{color:#d98b20;background:color-mix(in srgb,#d98b20 12%,transparent)}
.actions{display:flex;gap:6px;justify-content:flex-end}
footer,.modal-foot{display:flex;justify-content:space-between;align-items:center;gap:12px}
.modal-foot{justify-content:flex-end}
</style>
