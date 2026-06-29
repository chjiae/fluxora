<script setup lang="ts">
/**
 * 上游运行时故障状态管理页。
 *
 * 设计：
 * - 五行 Grid：页头 / 指标条 / 工具栏 / 表格区(1fr) / 分页；
 * - 列出所有非 AVAILABLE 的运行时资源（隔离/限流/认证失败等）；
 * - 管理员可手动恢复单个资源为 AVAILABLE，触发快照重建；
 * - 恢复后自动刷新列表 + 指标。
 */
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NIcon, useDialog, useMessage } from 'naive-ui'
import { RefreshCw, RotateCcw } from 'lucide-vue-next'
import MetricStrip from '@/components/MetricStrip.vue'
import {
  listRuntimeStates, recoverRuntimeState,
  scopeTypeLabels, runtimeStateMeta,
  type RuntimeStateRow,
} from '@/services/runtimeState'

/** ISO 时间戳转为本地可读格式，与 CredentialManagementPanel 一致 */
function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  try { return new Date(iso).toLocaleString() } catch { return iso }
}

const dialog = useDialog()
const message = useMessage()

// ========== 列表与指标 ==========

const rows = ref<RuntimeStateRow[]>([])
const loading = ref(false)

// 分页
const page = ref(1)
const size = 20
const total = computed(() => rows.value.length)
const pagedRows = computed(() => {
  const start = (page.value - 1) * size
  return rows.value.slice(start, start + size)
})

// 按状态分组统计
const stateStats = computed(() => {
  const map: Record<string, number> = {}
  for (const r of rows.value) {
    const key = runtimeStateMeta[r.runtimeState]?.label || r.runtimeState
    map[key] = (map[key] || 0) + 1
  }
  return map
})

const metricItems = computed(() => {
  const items: { label: string; value: number; tone?: 'plain' | 'warn' | 'danger' }[] = [{ label: '故障总数', value: rows.value.length }]
  for (const [label, count] of Object.entries(stateStats.value)) {
    const meta = Object.values(runtimeStateMeta).find(m => m.label === label)
    items.push({ label, value: count, tone: meta?.tone as 'danger' | 'warn' | undefined })
  }
  return items
})

async function loadList() {
  loading.value = true
  try {
    rows.value = await listRuntimeStates()
  } catch (e: any) {
    message.error(e.userMessage || '加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function recover(r: RuntimeStateRow) {
  dialog.warning({
    title: '确认恢复',
    content: `确定将「${r.resourceLabel}」恢复为可用状态吗？`,
    positiveText: '恢复',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await recoverRuntimeState(r.scopeType, r.scopeKey)
        message.success('已恢复，快照重建中')
        await loadList()
      } catch (e: any) {
        message.error(e.userMessage || '恢复失败，请稍后重试')
      }
    },
  })
}

onMounted(() => { void loadList() })

// ========== 表格列 ==========

const columns = computed(() => [
  {
    title: '资源', key: 'resource', minWidth: 200,
    render: (r: RuntimeStateRow) => h('div', { class: 'cell-duo' }, [
      h('div', { class: 'cell-primary' }, r.resourceLabel),
      h('div', { class: 'cell-meta' }, scopeTypeLabels[r.scopeType] || r.scopeType),
    ]),
  },
  {
    title: '状态', key: 'runtimeState', width: 120,
    render: (r: RuntimeStateRow) => {
      const meta = runtimeStateMeta[r.runtimeState]
      const color = meta?.tone === 'warn' ? '#d98b20' : 'var(--danger)'
      return h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
        h('span', { style: { display: 'inline-block', width: '7px', height: '7px', borderRadius: '50%', background: color, flexShrink: '0' } }),
        h('span', meta?.label || r.runtimeState),
      ])
    },
  },
  {
    title: '失败原因', key: 'lastFailureKind', width: 180,
    render: (r: RuntimeStateRow) => r.lastFailureKind || '—',
  },
  {
    title: '最近失败', key: 'lastFailedAt', width: 170,
    render: (r: RuntimeStateRow) => fmtTime(r.lastFailedAt),
  },
  {
    title: '冷却至', key: 'cooldownUntil', width: 170,
    render: (r: RuntimeStateRow) => fmtTime(r.cooldownUntil),
  },
  {
    title: '操作', key: 'actions', width: 100,
    render: (r: RuntimeStateRow) => h(NButton, {
      size: 'small', type: 'primary', secondary: true,
      onClick: () => recover(r),
    }, { icon: () => h(NIcon, null, () => h(RotateCcw, { size: 14 })), default: () => '恢复' }),
  },
])
</script>

<template>
  <div class="runtime-state-page">
    <!-- 页头 -->
    <div class="page-header">
      <h1>故障资源</h1>
      <p class="page-desc">被自动隔离、限流或因认证/计费等问题停用的上游资源</p>
    </div>

    <!-- 指标条 -->
    <MetricStrip :items="metricItems" />

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left" />
      <div class="toolbar-right">
        <n-button :loading="loading" @click="loadList">
          <template #icon><n-icon><RefreshCw :size="16" /></n-icon></template>刷新
        </n-button>
      </div>
    </div>

    <!-- 表格区 -->
    <div class="table-area">
      <n-data-table
        v-if="pagedRows.length"
        :columns="columns"
        :data="pagedRows"
        :row-key="(r: RuntimeStateRow) => `${r.scopeType}:${r.scopeKey}`"
        :single-line="false"
        :bordered="false"
        size="small"
        virtual-scroll
      />
      <div v-else class="empty-state">所有上游资源运行正常，无故障状态</div>
    </div>

    <!-- 分页 -->
    <div v-if="total > size" class="pagination">
      <n-pagination
        v-model:page="page"
        :page-size="size"
        :item-count="total"
        :page-slot="7"
      />
    </div>
  </div>
</template>

<style scoped>
.runtime-state-page {
  display: grid;
  grid-template-rows: auto auto auto 1fr auto;
  gap: 20px;
  height: 100%;
}

.page-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.page-desc {
  margin: 4px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.table-area {
  min-height: 0;
  overflow: auto;
}
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: var(--text-muted);
  font-size: 14px;
}

.pagination {
  display: flex;
  justify-content: center;
}

.cell-duo {
  display: flex;
  flex-direction: column;
}
.cell-primary {
  font-weight: 600;
  font-size: 13px;
}
.cell-meta {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
