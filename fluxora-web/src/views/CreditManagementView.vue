<script setup lang="ts">
/**
 * 额度管理 — 租户管理员 / 平台管理员视角。
 *
 * 双入口：
 *   - props.tenantId 指定时（平台视角嵌套路径 /console/tenants/:id/credit）：tenant 作用域；
 *   - 未指定（租户管理员 /console/credit/manage）：service 强制使用 currentUser.tenantId。
 *
 * 主体：用户额度账户列表（含可调整用户）+ 流水分页；
 * 调整额度通过内联 Modal（不弹 useDialog）完成，避免输入丢失。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NIcon } from 'naive-ui'
import { ArrowDownToLine, ArrowUpToLine, Search, Users, Wallet, X } from 'lucide-vue-next'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  adjustCredit,
  fetchAdjustableUsers,
  fetchTenantCreditStats,
  listTenantTransactions,
  type AdjustCreditRequest,
  type AdjustableUserOption,
  type CreditStats,
  type CreditTransactionPage,
  type CreditTransactionView,
} from '@/services/credit'

const props = defineProps<{ tenantId?: number }>()
const auth = useAuthStore()
const message = useMessage()
const dialog = useDialog()

const isPlatformView = computed(() => props.tenantId != null)
const effectiveTenantId = computed(() => props.tenantId ?? auth.user?.tenantId ?? null)

// ---------- 用户列表（可调整用户）----------
const users = ref<AdjustableUserOption[]>([])
const usersLoading = ref(false)
const userKeyword = ref('')

async function loadUsers() {
  usersLoading.value = true
  try {
    users.value = await fetchAdjustableUsers({
      tenantId: effectiveTenantId.value ?? undefined,
      keyword: userKeyword.value || undefined,
    })
  } catch { users.value = [] }
  finally { usersLoading.value = false }
}
watch(userKeyword, () => { loadUsers() })

// ---------- 流水 ----------
const txns = ref<CreditTransactionView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const txnKeyword = ref('')
const directionFilter = ref<'' | 'CREDIT' | 'DEBIT'>('')
const loading = ref(false)

async function loadTxns() {
  if (!effectiveTenantId.value) return
  loading.value = true
  try {
    const r: CreditTransactionPage = await listTenantTransactions(effectiveTenantId.value, {
      keyword: txnKeyword.value || undefined,
      direction: directionFilter.value || undefined,
      page: page.value,
      size: size.value,
    })
    txns.value = r.items
    total.value = r.total
  } catch { message.error('加载流水失败，请稍后重试') }
  finally { loading.value = false }
}
watch([txnKeyword, directionFilter], () => { page.value = 1; loadTxns() })
watch(page, () => loadTxns())

// ---------- 指标 ----------
const stats = ref<CreditStats | null>(null)
const statsLoading = ref(false)
async function loadStats() {
  if (!effectiveTenantId.value) return
  statsLoading.value = true
  try { stats.value = await fetchTenantCreditStats(effectiveTenantId.value) }
  catch { stats.value = null }
  finally { statsLoading.value = false }
}
const metricItems = computed(() => [
  { label: '账户总数', value: stats.value?.totalAccounts ?? null },
  { label: '当前余额合计', value: stats.value?.totalBalance ?? null },
  { label: '累计增加', value: stats.value?.totalCredits ?? null },
  { label: '累计扣减', value: stats.value?.totalDebits ?? null, tone: 'warn' as const },
  { label: '流水数', value: stats.value?.transactionCount ?? null },
])

async function refreshAfterAdjust() {
  await Promise.all([loadUsers(), loadTxns(), loadStats()])
}

onMounted(async () => {
  await Promise.all([loadUsers(), loadTxns(), loadStats()])
})

// ---------- 调整额度 Modal ----------
const adjustModalOpen = ref(false)
const adjustTarget = ref<AdjustableUserOption | null>(null)
const adjustForm = ref<{ direction: 'CREDIT' | 'DEBIT'; amount: string; reason: string }>({
  direction: 'CREDIT', amount: '', reason: '',
})
const adjustSubmitting = ref(false)
const adjustFormRef = ref<FormInst | null>(null)

const adjustRules: FormRules = {
  amount: [
    { required: true, message: '请输入调整金额', trigger: ['input', 'blur'] },
    {
      validator: (_r, v) => {
        if (!v) return new Error('请输入调整金额')
        const n = Number(v)
        if (Number.isNaN(n) || n <= 0) return new Error('金额必须为正数')
        return true
      },
      trigger: ['input', 'blur'],
    },
  ],
  reason: [
    { required: true, message: '请填写调整原因', trigger: ['input', 'blur'] },
    { max: 256, message: '原因最多 256 字符', trigger: ['input', 'blur'] },
  ],
}

function openAdjust(u: AdjustableUserOption, direction: 'CREDIT' | 'DEBIT' = 'CREDIT') {
  adjustTarget.value = u
  adjustForm.value = { direction, amount: '', reason: '' }
  adjustModalOpen.value = true
}
function closeAdjust() {
  adjustModalOpen.value = false
  adjustTarget.value = null
}

async function submitAdjust() {
  if (!adjustTarget.value || !effectiveTenantId.value) return
  try { await adjustFormRef.value?.validate() } catch { return }
  const req: AdjustCreditRequest = {
    direction: adjustForm.value.direction,
    amount: adjustForm.value.amount,
    reason: adjustForm.value.reason.trim(),
  }
  // 扣减额度做二次确认
  if (req.direction === 'DEBIT') {
    const ok = await new Promise<boolean>((resolve) => {
      dialog.warning({
        title: '确认扣减',
        content: `从 ${adjustTarget.value!.username} 扣减 ${req.amount} 额度。该操作将写入不可撤销的流水。`,
        positiveText: '确认扣减',
        negativeText: '取消',
        onPositiveClick: () => resolve(true),
        onNegativeClick: () => resolve(false),
        onClose: () => resolve(false),
      })
    })
    if (!ok) return
  }
  adjustSubmitting.value = true
  try {
    await adjustCredit(effectiveTenantId.value, adjustTarget.value.userId, req)
    message.success(req.direction === 'CREDIT' ? '额度已增加' : '额度已扣减')
    closeAdjust()
    await refreshAfterAdjust()
  } catch (e: any) {
    // 失败保留用户输入
    message.error(e?.userMessage || '调整失败，请稍后重试')
  } finally { adjustSubmitting.value = false }
}

// ---------- 用户表 ----------
const userColumns = computed<DataTableColumns<AdjustableUserOption>>(() => [
  { title: '用户', key: 'username', minWidth: 160,
    render: (row) => h('div', { class: 'cell-user' }, [
      h('div', { class: 'cell-user-name' }, row.userDisplayName || row.username),
      h('div', { class: 'cell-user-uname' }, row.username),
    ]),
  },
  ...(isPlatformView.value
    ? []
    : [{
        title: '租户', key: 'tenantName', width: 160,
        render: (row: AdjustableUserOption) => h('span', { class: 'cell-muted' }, row.tenantName),
      } as any]),
  { title: '余额', key: 'balance', width: 140, align: 'right',
    render: (row) => h('span', { class: 'cell-balance' }, row.balance),
  },
  { title: '', key: '__act', width: 180, align: 'right',
    render: (row) => h('div', { class: 'cell-actions' }, [
      h(NButton, { size: 'small', type: 'primary', ghost: true, onClick: () => openAdjust(row, 'CREDIT') },
        { default: () => '增加' }),
      h(NButton, { size: 'small', onClick: () => openAdjust(row, 'DEBIT') }, { default: () => '扣减' }),
    ]),
  },
])

// ---------- 流水表 ----------
function formatType(row: CreditTransactionView) {
  const byType: Record<string, string> = {
    MANUAL_ADJUSTMENT: row.direction === 'CREDIT' ? '人工增加' : '人工扣减',
    CARD_REDEEM: '卡密充值',
    MODEL_USAGE: '模型结算',
  }
  return byType[row.transactionType] || (row.direction === 'CREDIT' ? '增加' : '扣减')
}
function amountClass(row: CreditTransactionView) {
  return row.direction === 'CREDIT' ? 'credit' : 'debit'
}
function formatAmount(row: CreditTransactionView) { return (amountClass(row) === 'credit' ? '+' : '-') + row.amount }
function timeAgo(iso: string | null | undefined) { return iso ? iso.slice(0, 16).replace('T', ' ') : '—' }

const txnColumns = computed<DataTableColumns<CreditTransactionView>>(() => [
  { title: '用户', key: 'username', minWidth: 140,
    render: (row) => h('div', { class: 'cell-user' }, [
      h('div', { class: 'cell-user-name' }, row.userDisplayName || row.username),
      h('div', { class: 'cell-user-uname' }, row.username),
    ]),
  },
  { title: '类型', key: 'direction', width: 90,
    render: (row) => h('span', { class: `txn-dir txn-${amountClass(row)}` }, formatType(row)),
  },
  { title: '变更', key: 'amount', width: 120, align: 'right',
    render: (row) => h('span', { class: `txn-amount txn-${amountClass(row)}` }, formatAmount(row)),
  },
  { title: '变更后', key: 'balanceAfter', width: 120, align: 'right',
    render: (row) => h('span', { class: 'cell-num' }, row.balanceAfter),
  },
  { title: '原因', key: 'reason', minWidth: 180,
    render: (row) => h('span', { class: 'cell-muted' }, row.reason),
  },
  { title: '操作人', key: 'operatorName', width: 120,
    render: (row) => h('span', { class: 'cell-muted' }, row.operatorName || '系统'),
  },
  { title: '时间', key: 'createdAt', width: 150,
    render: (row) => h('span', { class: 'cell-muted' }, timeAgo(row.createdAt)),
  },
])

const showPagination = computed(() => total.value > size.value)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
</script>

<template>
  <section class="credit-mgmt">
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>额度管理</h1>
        <p>{{ isPlatformView ? '为指定租户用户增加或扣减额度。' : '为本租户用户增加或扣减额度；流水不可篡改。' }}</p>
      </div>
    </header>

    <MetricStrip :items="metricItems" :loading="statsLoading" />

    <!-- 上半：用户列表（含调整按钮）-->
    <section class="block">
      <header class="block-hdr">
        <h2><Users :size="16" class="block-icon" /> 可调整用户</h2>
        <label class="search small">
          <n-icon class="search-icon" :size="14"><Search /></n-icon>
          <input v-model="userKeyword" class="search-input" type="text" placeholder="搜索用户名" aria-label="搜索用户" />
          <button v-if="userKeyword" type="button" class="search-clear" aria-label="清除" @click="userKeyword = ''">
            <n-icon :size="12"><X /></n-icon></button>
        </label>
      </header>
      <n-data-table v-if="users.length || usersLoading" :columns="userColumns" :data="users" :loading="usersLoading"
        :pagination="false" :bordered="false" :single-line="false" class="user-table" size="small" />
      <div v-else class="empty">
        <n-empty description="没有可调整额度的用户"><template #icon><n-icon :size="32"><Wallet /></n-icon></template></n-empty>
      </div>
    </section>

    <!-- 下半：流水列表 -->
    <section class="block">
      <header class="block-hdr">
        <h2>流水记录</h2>
        <div class="block-tools">
          <div class="chip-group" role="radiogroup" aria-label="类型筛选">
            <button v-for="opt in [{label:'全部',value:'' as const},{label:'增加',value:'CREDIT' as const},{label:'扣减',value:'DEBIT' as const}]"
              :key="opt.value" type="button" class="chip" :class="{'is-active':directionFilter===opt.value}"
              @click="directionFilter=opt.value">{{ opt.label }}</button>
          </div>
        </div>
      </header>
      <n-data-table v-if="txns.length || loading" :columns="txnColumns" :data="txns" :loading="loading"
        :pagination="false" :bordered="false" :single-line="false" class="txn-table" size="small" />
      <div v-else class="empty">
        <n-empty description="还没有流水"><template #icon><n-icon :size="32"><Wallet /></n-icon></template></n-empty>
      </div>
      <footer v-if="showPagination" class="block-foot">
        <span class="muted">共 <strong>{{ total }}</strong> 条</span>
        <n-pagination v-model:page="page" :page-count="pageCount" :page-size="size" size="small" />
      </footer>
    </section>

    <!-- 调整额度 Modal -->
    <n-modal :show="adjustModalOpen" preset="card" class="credit-modal" :bordered="false"
      :segmented="{content:'soft'}" style="width:min(520px,calc(100vw-32px))"
      @update:show="(v:boolean)=>{if(!v)closeAdjust()}">
      <template #header>
        <div class="modal-head">
          <h2>调整额度</h2>
          <span class="modal-head-code" v-if="adjustTarget">{{ adjustTarget.userDisplayName || adjustTarget.username }} · 当前 {{ adjustTarget.balance }}</span>
        </div>
      </template>
      <div v-if="adjustTarget">
        <n-form ref="adjustFormRef" :model="adjustForm" :rules="adjustRules" label-placement="top">
          <n-form-item label="操作类型">
            <n-radio-group v-model:value="adjustForm.direction">
              <n-radio-button value="CREDIT">
                <n-icon><ArrowUpToLine /></n-icon> 增加额度
              </n-radio-button>
              <n-radio-button value="DEBIT">
                <n-icon><ArrowDownToLine /></n-icon> 扣减额度
              </n-radio-button>
            </n-radio-group>
          </n-form-item>
          <n-form-item label="金额" path="amount">
            <n-input v-model:value="adjustForm.amount" placeholder="例如 100.0000，精确到 4 位小数" />
          </n-form-item>
          <n-form-item label="原因" path="reason">
            <n-input v-model:value="adjustForm.reason" type="textarea" :autosize="{minRows:2,maxRows:4}"
              placeholder="例如 测试余额初始化 / 补偿月度故障损失" />
          </n-form-item>
        </n-form>
        <p v-if="adjustForm.direction==='DEBIT'" class="hint-warn">
          扣减将立即从用户当前余额中扣除；如余额不足将自动拒绝。
        </p>
      </div>
      <template #footer>
        <div class="modal-foot">
          <n-button @click="closeAdjust">取消</n-button>
          <n-button type="primary" :loading="adjustSubmitting" @click="submitAdjust">提交</n-button>
        </div>
      </template>
    </n-modal>
  </section>
</template>

<style scoped>
.credit-mgmt{display:flex;flex-direction:column;gap:20px;height:100%;min-height:0}
.page-hdr{display:flex;justify-content:space-between;align-items:flex-end;gap:16px;flex-wrap:wrap}
.page-hdr-text h1{margin:0;font-size:22px;font-weight:650;letter-spacing:-.01em}
.page-hdr-text p{margin:4px 0 0;color:var(--text-muted);font-size:13px}

.block{display:flex;flex-direction:column;background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:14px 16px 8px}
.block-hdr{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-bottom:10px;border-bottom:1px solid var(--border);margin-bottom:8px;flex-wrap:wrap}
.block-hdr h2{margin:0;font-size:14px;font-weight:600;display:inline-flex;align-items:center;gap:6px}
.block-icon{color:var(--text-muted)}
.block-tools{display:flex;align-items:center;gap:10px}
.block-foot{display:flex;justify-content:space-between;align-items:center;padding:10px 0 4px}
.block-foot .muted{font-size:13px;color:var(--text-muted)}
.block-foot .muted strong{color:var(--text);font-weight:600}

.search{position:relative;display:flex;align-items:center}
.search.small{width:200px}
.search-icon{position:absolute;left:8px;color:var(--text-muted);pointer-events:none}
.search-input{width:100%;height:30px;padding:0 28px;font:inherit;font-size:13px;color:var(--text);background:var(--surface);border:1px solid var(--border);border-radius:6px;outline:none}
.search-input:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--focus-ring)}
.search-clear{position:absolute;right:6px;display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;padding:0;border:0;border-radius:4px;background:transparent;color:var(--text-muted);cursor:pointer}

.chip-group{display:inline-flex;padding:2px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:7px}
.chip{appearance:none;border:0;background:transparent;padding:3px 10px;font:inherit;font-size:12px;color:var(--text-muted);border-radius:5px;cursor:pointer}
.chip:hover{color:var(--text)}
.chip.is-active{background:var(--surface);color:var(--text);box-shadow:0 1px 2px rgba(0,0,0,.06)}
[data-theme="dark"] .chip.is-active{background:#2a2a28;box-shadow:none}

.user-table,.txn-table{flex:1;min-height:0}
.empty{padding:32px 16px;display:flex;align-items:center;justify-content:center}
.cell-user-name{font-weight:600;color:var(--text);line-height:1.3}
.cell-user-uname{margin-top:2px;font-family:var(--font-mono),monospace;font-size:12px;color:var(--text-muted)}
.cell-muted{color:var(--text-muted);font-size:13px}
.cell-balance{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;font-weight:600}
.cell-num{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;color:var(--text-muted)}
.cell-actions{display:flex;justify-content:flex-end;gap:6px}
.txn-dir{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:500}
.txn-dir.txn-credit{color:#20a779;background:color-mix(in srgb,#20a779 12%,transparent)}
.txn-dir.txn-debit{color:#d98b20;background:color-mix(in srgb,#d98b20 12%,transparent)}
.txn-amount{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;font-weight:600}
.txn-amount.txn-credit{color:#20a779}
.txn-amount.txn-debit{color:#d98b20}

:deep(.credit-modal){border-radius:14px}
.modal-head h2{margin:0;font-size:17px;font-weight:650;letter-spacing:-.005em}
.modal-head-code{display:block;margin-top:4px;font-family:var(--font-mono),monospace;font-size:12px;color:var(--text-muted)}
.modal-foot{display:flex;justify-content:flex-end;gap:8px}
.hint-warn{margin:8px 0 0;font-size:12px;color:var(--danger)}
</style>
