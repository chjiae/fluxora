<script setup lang="ts">
/**
 * RechargeCardRevealPanel — 一次性卡密展示弹窗（批量版）。
 *
 * 安全约束（AGENT.md 完整卡密安全规则）：
 *   1. 仅在批次创建响应中展示一次完整 plaintexts；
 *   2. :closable=false :mask-closable=false，必须主动点「我已妥善保存」才能关闭；
 *   3. 关闭后立刻 emit('close')，父组件应同步清空 plaintexts ref；
 *   4. 不写入 localStorage / sessionStorage / URL / cookie；不在 <input> 中渲染；
 *   5. 不在 console / toast / 日志中复述完整卡密；
 *   6. 复制 / 导出均基于内存中的 plaintexts，不调任何后端接口；
 *   7. 提供 TXT 与 CSV 两种本地导出（Blob + URL.createObjectURL + <a download>）。
 */
import { computed, ref } from 'vue'
import { AlertTriangle, Check, Copy, Download } from 'lucide-vue-next'

const props = defineProps<{
  show: boolean
  /** 完整卡密明文列表；本组件销毁后必须清空 */
  plaintexts: string[]
  /** 批次摘要：batchCode + denomination + count，用于结果页头部展示 */
  batches: Array<{
    batchCode: string
    name: string | null
    denomination: string
    totalCount: number
  }>
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

const copiedAll = ref(false)
const copyError = ref(false)

const totalCount = computed(() => props.plaintexts.length)
const totalValue = computed(() => {
  let sum = 0
  for (const b of props.batches) {
    sum += Number(b.denomination) * b.totalCount
  }
  return sum.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
})

async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(text)
      return true
    }
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

async function copyAll() {
  copiedAll.value = false; copyError.value = false
  const ok = await writeClipboard(props.plaintexts.join('\n'))
  if (ok) {
    copiedAll.value = true
    setTimeout(() => { copiedAll.value = false }, 2000)
  } else {
    copyError.value = true
  }
}

const copiedOne = ref<string | null>(null)
async function copyOne(code: string) {
  const ok = await writeClipboard(code)
  if (ok) {
    copiedOne.value = code
    setTimeout(() => { if (copiedOne.value === code) copiedOne.value = null }, 1500)
  }
}

function timestamp() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
}

function exportTxt() {
  const lines: string[] = []
  // 头部摘要 + 每个批次 + 卡密
  for (const b of props.batches) {
    lines.push(`# 批次 ${b.batchCode} · 面额 ${b.denomination} · 数量 ${b.totalCount}`)
    if (b.name) lines.push(`# 备注: ${b.name}`)
  }
  lines.push('')
  for (const code of props.plaintexts) lines.push(code)
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  triggerDownload(blob, `fluxora-cards-${timestamp()}.txt`)
}

function exportCsv() {
  // CSV: 第一列卡密、第二列批次编号、第三列面额
  // 卡密按批次顺序排列；每个批次的 totalCount 张连续
  const rows: string[] = ['card_code,batch_code,denomination,batch_name']
  let cursor = 0
  for (const b of props.batches) {
    for (let i = 0; i < b.totalCount && cursor < props.plaintexts.length; i++) {
      const code = props.plaintexts[cursor++]
      const name = (b.name || '').replace(/"/g, '""')
      rows.push(`${code},${b.batchCode},${b.denomination},"${name}"`)
    }
  }
  const blob = new Blob([rows.join('\n')], { type: 'text/csv;charset=utf-8' })
  triggerDownload(blob, `fluxora-cards-${timestamp()}.csv`)
}

function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function confirmClose() {
  copiedAll.value = false
  copyError.value = false
  copiedOne.value = null
  emit('close')
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :closable="false"
    :mask-closable="false"
    :bordered="false"
    :segmented="{ content: 'soft' }"
    class="card-reveal-modal"
    style="width: min(720px, calc(100vw - 32px))"
  >
    <template #header>
      <div class="reveal-head">
        <h2>卡密已创建</h2>
        <span class="reveal-head-meta">共 {{ totalCount }} 张 · 总面值 {{ totalValue }}</span>
      </div>
    </template>

    <div class="reveal-body">
      <!-- 警告区 -->
      <div class="reveal-warn">
        <n-icon :size="18"><AlertTriangle /></n-icon>
        <span>完整卡密仅在本次生成后展示一次，请立即导出并妥善保存。关闭页面、刷新页面或稍后重新进入均无法再次查看完整卡密。</span>
      </div>

      <!-- 批次摘要 -->
      <div class="reveal-batches">
        <div v-for="b in batches" :key="b.batchCode" class="reveal-batch-row">
          <span class="batch-code">{{ b.batchCode }}</span>
          <span class="batch-meta">面额 {{ b.denomination }} · {{ b.totalCount }} 张</span>
          <span v-if="b.name" class="batch-note">{{ b.name }}</span>
        </div>
      </div>

      <!-- 操作区 -->
      <div class="reveal-actions">
        <n-button v-if="!copiedAll" type="primary" ghost @click="copyAll">
          <template #icon><n-icon><Copy /></n-icon></template>
          复制全部
        </n-button>
        <n-button v-else type="success" ghost disabled>
          <template #icon><n-icon><Check /></n-icon></template>
          已复制全部
        </n-button>
        <n-button @click="exportTxt">
          <template #icon><n-icon><Download /></n-icon></template>
          导出 TXT
        </n-button>
        <n-button @click="exportCsv">
          <template #icon><n-icon><Download /></n-icon></template>
          导出 CSV
        </n-button>
        <span v-if="copyError" class="copy-error">复制失败，请手动复制</span>
      </div>

      <!-- 卡密列表 -->
      <div class="reveal-list">
        <div v-for="(code, idx) in plaintexts" :key="idx" class="reveal-row">
          <span class="row-idx">{{ idx + 1 }}</span>
          <code class="row-code">{{ code }}</code>
          <button
            class="row-copy"
            type="button"
            @click="copyOne(code)"
            :class="{ 'is-copied': copiedOne === code }"
            :aria-label="`复制第 ${idx + 1} 张卡密`"
          >
            <n-icon :size="14">
              <Check v-if="copiedOne === code" />
              <Copy v-else />
            </n-icon>
            {{ copiedOne === code ? '已复制' : '复制' }}
          </button>
        </div>
      </div>

      <p class="reveal-tip">
        建议导出 TXT 或 CSV 后导入密钥管理工具或加密表格中保存。关闭后页面只能看到 <code>FLX-XXXX...</code> 前缀。
      </p>
    </div>

    <template #footer>
      <div class="reveal-foot">
        <n-button type="primary" @click="confirmClose">我已妥善保存全部卡密</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
:deep(.card-reveal-modal){border-radius:14px}
.reveal-head h2{margin:0;font-size:17px;font-weight:650;letter-spacing:-.005em}
.reveal-head-meta{display:block;margin-top:4px;font-family:var(--font-mono),monospace;font-size:12px;color:var(--text-muted)}
.reveal-body{display:flex;flex-direction:column;gap:14px;padding-top:4px}

.reveal-warn{
  display:flex;align-items:center;gap:10px;padding:12px 14px;
  background:color-mix(in srgb,var(--danger) 8%,transparent);
  border:1px solid color-mix(in srgb,var(--danger) 30%,var(--border));
  border-radius:10px;font-size:13px;color:var(--danger);
}
[data-theme="dark"] .reveal-warn{background:color-mix(in srgb,var(--danger) 12%,transparent)}

.reveal-batches{display:flex;flex-direction:column;gap:6px;padding:10px 12px;background:var(--surface-elevated);border:1px solid var(--border);border-radius:8px;font-size:12.5px}
.reveal-batch-row{display:flex;gap:12px;flex-wrap:wrap;align-items:center}
.batch-code{font-family:var(--font-mono),monospace;color:var(--text);font-weight:600}
.batch-meta{color:var(--text-muted)}
.batch-note{color:var(--text-muted);font-style:italic}

.reveal-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.copy-error{font-size:12.5px;color:var(--danger)}

.reveal-list{
  max-height:280px;overflow:auto;
  border:1px solid var(--border);border-radius:8px;
  background:var(--surface);
}
.reveal-row{display:flex;align-items:center;gap:10px;padding:6px 10px;border-bottom:1px solid var(--border);font-size:13px}
.reveal-row:last-child{border-bottom:0}
.row-idx{color:var(--text-muted);font-size:11.5px;min-width:30px;text-align:right;font-variant-numeric:tabular-nums}
.row-code{flex:1;font-family:var(--font-mono),monospace;font-size:13px;color:var(--text);user-select:all}
.row-copy{
  appearance:none;border:1px solid var(--border);background:var(--surface);
  color:var(--text-muted);padding:3px 8px;border-radius:4px;
  font:inherit;font-size:11.5px;cursor:pointer;display:inline-flex;align-items:center;gap:4px;
  transition:background .15s ease,color .15s ease;
}
.row-copy:hover{background:var(--surface-elevated);color:var(--text)}
.row-copy.is-copied{color:#20a779;border-color:#20a779}

.reveal-tip{margin:0;font-size:12.5px;color:var(--text-muted);line-height:1.6}
.reveal-tip code{font-family:var(--font-mono),monospace;font-size:12px}
.reveal-foot{display:flex;justify-content:flex-end}
</style>
