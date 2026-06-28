<script setup lang="ts">
/**
 * 卡密充值页面（普通用户视角）。
 *
 * 顶部：卡密输入区（含格式提示、容错说明、提交按钮、当前余额）
 * 下方：本人卡密充值流水（仅 source=CARD_REDEEM）
 */
import { computed, h, onMounted, ref } from 'vue'
import { CreditCard, Wallet } from 'lucide-vue-next'
import type { DataTableColumns } from 'naive-ui'
import { redeemCard, type RedeemResponse } from '@/services/card'
import { fetchMyAccount, listMyTransactions, type CreditAccountView, type CreditTransactionView } from '@/services/credit'

const message = useMessage()

const account = ref<CreditAccountView | null>(null)
const accountLoading = ref(false)

const txns = ref<CreditTransactionView[]>([])
const txnsLoading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)

const code = ref('')
const submitting = ref(false)
const lastResult = ref<RedeemResponse | null>(null)

async function loadAccount() {
  accountLoading.value = true
  try { account.value = await fetchMyAccount() } catch { account.value = null }
  finally { accountLoading.value = false }
}

async function loadTxns() {
  txnsLoading.value = true
  try {
    // 仅显示 CARD_REDEEM 充值；通过 keyword 字段无法过滤 source，但前端可后置过滤
    const r = await listMyTransactions({ page: page.value, size: size.value, direction: 'CREDIT' })
    // 后端尚未提供 source 过滤参数；通过 reason 前缀「卡密充值」识别
    txns.value = r.items.filter(t => t.reason.startsWith('卡密充值'))
    total.value = txns.value.length
  } catch { message.error('加载充值记录失败，请稍后重试') }
  finally { txnsLoading.value = false }
}

async function handleRedeem() {
  if (!code.value || !code.value.trim()) {
    message.error('请输入卡密')
    return
  }
  submitting.value = true
  lastResult.value = null
  try {
    const r = await redeemCard(code.value.trim())
    lastResult.value = r
    message.success(`充值成功 +${r.amount}`)
    code.value = ''
    await Promise.all([loadAccount(), loadTxns()])
  } catch (e: any) {
    // 失败保留输入，方便用户检查重试
    message.error(e?.userMessage || '卡密核销失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => { await Promise.all([loadAccount(), loadTxns()]) })

function timeAgo(iso: string | null | undefined) {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '—'
}

const columns = computed<DataTableColumns<CreditTransactionView>>(() => [
  { title: '类型', key: 'direction', width: 100,
    render: () => h('span', { class: 'txn-dir txn-card' }, '卡密充值'),
  },
  { title: '充值额度', key: 'amount', width: 130, align: 'right',
    render: (row) => h('span', { class: 'txn-amount' }, '+' + row.amount),
  },
  { title: '变更前', key: 'balanceBefore', width: 120, align: 'right',
    render: (row) => h('span', { class: 'cell-num' }, row.balanceBefore),
  },
  { title: '变更后', key: 'balanceAfter', width: 120, align: 'right',
    render: (row) => h('span', { class: 'cell-num' }, row.balanceAfter),
  },
  { title: '来源说明', key: 'reason', minWidth: 200,
    render: (row) => h('span', { class: 'cell-muted' }, row.reason),
  },
  { title: '充值时间', key: 'createdAt', width: 150,
    render: (row) => h('span', { class: 'cell-muted' }, timeAgo(row.createdAt)),
  },
])

const hasFilter = computed(() => false)
</script>

<template>
  <section class="card-redeem">
    <header class="page-hdr">
      <div class="page-hdr-text">
        <h1>卡密充值</h1>
        <p>输入完整卡密以兑换为可用额度；卡密成功核销后立即写入额度账户与不可篡改流水。</p>
      </div>
    </header>

    <!-- 卡密输入区 -->
    <section class="redeem-card">
      <header class="redeem-hd">
        <h2><CreditCard :size="16" class="hd-icon" /> 输入卡密</h2>
        <div class="balance-pill" v-if="account">
          当前余额 <strong>{{ account.balance }}</strong>
        </div>
      </header>
      <div class="redeem-input-row">
        <n-input
          v-model:value="code"
          placeholder="FLX-XXXX-XXXX-XXXX-XXXX-XXXX"
          :disabled="submitting"
          @keyup.enter="handleRedeem"
          size="large"
        />
        <n-button type="primary" size="large" :loading="submitting" @click="handleRedeem">
          核销充值
        </n-button>
      </div>
      <p class="redeem-hint">
        卡密格式：<code>FLX</code> 前缀 + 5 段共 20 字符；可包含或省略空格、连字符、大小写均可。
      </p>

      <!-- 最近一次成功结果 -->
      <div v-if="lastResult" class="redeem-result">
        <div class="result-line">
          <span class="result-label">本次充值</span>
          <span class="result-value">+{{ lastResult.amount }}</span>
        </div>
        <div class="result-line">
          <span class="result-label">充值后余额</span>
          <span class="result-value">{{ lastResult.newBalance }}</span>
        </div>
        <div class="result-line">
          <span class="result-label">来源</span>
          <span class="result-value">卡密充值 · {{ lastResult.cardPrefix }}</span>
        </div>
      </div>
    </section>

    <!-- 卡密充值流水 -->
    <section class="block">
      <header class="block-hdr">
        <h2><Wallet :size="16" class="hd-icon" /> 我的卡密充值记录</h2>
      </header>
      <n-data-table
        v-if="txns.length || txnsLoading"
        :columns="columns" :data="txns" :loading="txnsLoading"
        :pagination="false" :bordered="false" :single-line="false"
        class="card-txn-table" size="small"
      />
      <div v-else class="empty">
        <n-empty :description="hasFilter ? '没有匹配的记录' : '还没有卡密充值记录'">
          <template #icon><n-icon :size="32"><CreditCard /></n-icon></template>
        </n-empty>
      </div>
    </section>
  </section>
</template>

<style scoped>
.card-redeem{display:flex;flex-direction:column;gap:20px;height:100%;min-height:0}
.page-hdr{display:flex;justify-content:space-between;align-items:flex-end;gap:16px;flex-wrap:wrap}
.page-hdr-text h1{margin:0;font-size:22px;font-weight:650;letter-spacing:-.01em}
.page-hdr-text p{margin:4px 0 0;color:var(--text-muted);font-size:13px}

.redeem-card{background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:18px 20px}
.redeem-hd{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap}
.redeem-hd h2{margin:0;font-size:14px;font-weight:600;display:inline-flex;align-items:center;gap:6px}
.hd-icon{color:var(--text-muted)}
.balance-pill{font-size:13px;color:var(--text-muted)}
.balance-pill strong{color:var(--text);font-weight:600;font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums}

.redeem-input-row{display:flex;gap:10px;align-items:center}
.redeem-input-row :deep(.n-input){flex:1;font-family:var(--font-mono),monospace}

.redeem-hint{margin:10px 0 0;font-size:12.5px;color:var(--text-muted)}
.redeem-hint code{font-family:var(--font-mono),monospace;font-size:12px;color:var(--text)}

.redeem-result{
  margin-top:16px;padding:12px 14px;
  background:color-mix(in srgb,#20a779 8%,transparent);
  border:1px solid color-mix(in srgb,#20a779 25%,var(--border));
  border-radius:8px;display:flex;flex-direction:column;gap:6px;font-size:13px;
}
.result-line{display:flex;justify-content:space-between;gap:12px}
.result-label{color:var(--text-muted)}
.result-value{color:var(--text);font-weight:600;font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums}

.block{display:flex;flex-direction:column;background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:14px 16px 8px;min-height:0}
.block-hdr{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-bottom:10px;border-bottom:1px solid var(--border);margin-bottom:8px}
.block-hdr h2{margin:0;font-size:14px;font-weight:600;display:inline-flex;align-items:center;gap:6px}

.card-txn-table{flex:1;min-height:0}
.empty{padding:32px 16px;display:flex;align-items:center;justify-content:center}
:deep(.card-txn-table .n-data-table-th){font-weight:550;font-size:12px;color:var(--text-muted);letter-spacing:.01em;background:transparent}
:deep(.card-txn-table .n-data-table-td){padding-top:12px;padding-bottom:12px;font-size:13.5px}
:deep(.card-txn-table .n-data-table-tr:hover .n-data-table-td){background:var(--surface-elevated)}
.txn-dir{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:500;color:#20a779;background:color-mix(in srgb,#20a779 12%,transparent)}
.txn-amount{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;font-weight:600;color:#20a779}
.cell-num{font-family:var(--font-mono),monospace;font-variant-numeric:tabular-nums;color:var(--text-muted)}
.cell-muted{color:var(--text-muted);font-size:13px}

@media(max-width:720px){
  .redeem-input-row{flex-direction:column;align-items:stretch}
}
</style>
