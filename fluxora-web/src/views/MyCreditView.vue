<script setup lang="ts">
/**
 * 我的额度 — 普通租户用户视角。
 *
 * 显示：余额（大数）+ 自己的流水分页。
 * 普通用户**没有** 增加/扣减额度的入口（按 AGENT.md 规范）。
 *
 * 布局：4 行 Grid（页头 / 余额条 / 工具栏 / 表格 1fr / 分页）；余额条复用 MetricStrip。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { Search, Wallet, X } from 'lucide-vue-next'
import type { DataTableColumns } from 'naive-ui'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  fetchMyAccount,
  listMyTransactions,
  type CreditAccountView,
  type CreditTransactionPage,
  type CreditTransactionView,
} from '@/services/credit'

const message = useMessage()

const account = ref<CreditAccountView | null>(null)
const accountLoading = ref(false)

const txns = ref<CreditTransactionView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const directionFilter = ref<'' | 'CREDIT' | 'DEBIT'>('')
const loading = ref(false)

async function loadAccount() {
  accountLoading.value = true
  try { account.value = await fetchMyAccount() }
  catch (e: any) {
    account.value = null
    if (e?.userMessage && e?.userMessage !== '服务暂时不可用，请稍后重试') {
      message.info(e.userMessage)
    }
  }
  finally { accountLoading.value = false }
}

async function loadTxns() {
  loading.value = true
  try {
    const r: CreditTransactionPage = await listMyTransactions({
      keyword: keyword.value || undefined,
      direction: directionFilter.value || undefined,
      page: page.value,
      size: size.value,
    })
    txns.value = r.items
    total.value = r.total
  } catch { message.error('加载流水失败，请稍后重试') }
  finally { loading.value = false }
}

watch([keyword, directionFilter], () => { page.value = 1; loadTxns() })
watch(page, () => loadTxns())

onMounted(async () => { await Promise.all([loadAccount(), loadTxns()]) })

const metricItems = computed(() => [
  { label: '当前余额', value: account.value?.balance ?? null },
  { label: '流水总数', value: total.value },
])
const isInsufficient = computed(() => account.value != null && Number(account.value.balance) <= 0)

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
function formatAmount(row: CreditTransactionView) {
  return (amountClass(row) === 'credit' ? '+' : '-') + row.amount
}
function timeAgo(iso: string | null | undefined) {
  if (!iso) return '—'
  return iso.slice(0, 16).replace('T', ' ')
}

const columns = computed<DataTableColumns<CreditTransactionView>>(() => [
  { title: '类型', key: 'direction', width: 90,
    render: (row) => h('span', { class: `txn-dir txn-${amountClass(row)}` }, formatType(row)),
  },
  { title: '变更', key: 'amount', width: 130, align: 'right',
    render: (row) => h('span', { class: `txn-amount txn-${amountClass(row)}` }, formatAmount(row)),
  },
  { title: '变更前', key: 'balanceBefore', width: 120, align: 'right',
    render: (row) => h('span', { class: 'cell-num' }, row.balanceBefore),
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
const hasFilter = computed(() => !!keyword.value || !!directionFilter.value)

function resetFilters() {
  keyword.value = ''
  directionFilter.value = ''
  page.value = 1
  loadTxns()
}
</script>

<template>
  <section class="credit-page">
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>我的额度</h1>
        <p>查看你的当前余额和历史流水。</p>
      </div>
    </header>

    <MetricStrip :items="metricItems" :loading="accountLoading" />
    <p v-if="isInsufficient" class="balance-alert">当前额度不足，暂时无法发起新的模型请求，请充值后重试。</p>

    <div class="toolbar">
      <label class="search">
        <n-icon class="search-icon" :size="16"><Search /></n-icon>
        <input v-model="keyword" class="search-input" type="text" placeholder="搜索原因" aria-label="搜索流水" />
        <button v-if="keyword" type="button" class="search-clear" aria-label="清除搜索" @click="keyword = ''">
          <n-icon :size="14"><X /></n-icon></button>
      </label>
      <div class="chip-group" role="radiogroup" aria-label="类型筛选">
        <button v-for="opt in [{label:'全部',value:'' as const},{label:'增加',value:'CREDIT' as const},{label:'扣减',value:'DEBIT' as const}]"
          :key="opt.value" type="button" class="chip" :class="{'is-active':directionFilter===opt.value}" role="radio"
          :aria-checked="directionFilter===opt.value" @click="directionFilter=opt.value">{{ opt.label }}</button>
      </div>
      <button v-if="hasFilter" type="button" class="reset-btn" @click="resetFilters">重置</button>
    </div>

    <div class="table-region">
      <n-data-table v-if="txns.length || loading" :columns="columns" :data="txns" :loading="loading"
        :pagination="false" :bordered="false" :single-line="false" flex-height class="credit-table" size="medium" />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有符合条件的流水' : '还没有额度流水'">
          <template #icon><n-icon :size="36"><Wallet /></n-icon></template>
        </n-empty>
      </div>
    </div>
    <footer class="page-foot"><span class="page-foot-summary">共 <strong>{{ total }}</strong> 条流水</span>
      <n-pagination v-if="showPagination" v-model:page="page" :page-count="pageCount" :page-size="size" size="small" /></footer>
  </section>
</template>

<style scoped>
.credit-page{height:100%;display:grid;grid-template-rows:auto auto auto 1fr auto;gap:20px;min-height:0}
.page-hdr{display:flex;justify-content:space-between;align-items:flex-end;gap:16px;flex-wrap:wrap}
.page-hdr-text h1{margin:0;font-size:22px;font-weight:650;letter-spacing:-.01em}
.page-hdr-text p{margin:4px 0 0;color:var(--text-muted);font-size:13px}
.toolbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.balance-alert{margin:0;padding:10px 12px;border:1px solid color-mix(in srgb,var(--danger) 24%,var(--border));border-radius:8px;background:color-mix(in srgb,var(--danger) 8%,transparent);color:var(--danger);font-size:13px}
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
.credit-table{flex:1;min-height:0}
.empty{flex:1;display:flex;align-items:center;justify-content:center;padding:48px 16px}
:deep(.credit-table .n-data-table-th){font-weight:550;font-size:12px;color:var(--text-muted);letter-spacing:.01em;background:transparent}
:deep(.credit-table .n-data-table-td){padding-top:12px;padding-bottom:12px;font-size:13.5px}
:deep(.credit-table .n-data-table-tr:hover .n-data-table-td){background:var(--surface-elevated)}
.txn-dir{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:500}
.txn-dir.txn-credit{color:#20a779;background:color-mix(in srgb,#20a779 12%,transparent)}
.txn-dir.txn-debit{color:#d98b20;background:color-mix(in srgb,#d98b20 12%,transparent)}
.txn-amount{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;font-weight:600}
.txn-amount.txn-credit{color:#20a779}
.txn-amount.txn-debit{color:#d98b20}
.cell-num{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;color:var(--text-muted)}
.cell-muted{color:var(--text-muted);font-size:13px}
.page-foot{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
.page-foot-summary{font-size:13px;color:var(--text-muted)}
.page-foot-summary strong{color:var(--text);font-weight:600}
</style>
