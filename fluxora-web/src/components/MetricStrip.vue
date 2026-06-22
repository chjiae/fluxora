<script setup lang="ts">
/**
 * MetricStrip — 印刷品式指标条
 *
 * 用于控制台页面顶部展示聚合指标。设计选择（区别于常见 SaaS metric cards）：
 *
 *   - 不用「大数字 + 渐变图标」的卡片模板（impeccable 绝对禁止 hero-metric template）；
 *   - 不用 4-up 等大网格（避免 identical card grids），改为单行紧凑分隔；
 *   - 标签在上、数值在下；数值采用 tabular-nums 等宽数字，列与列对齐；
 *   - 项目之间用 1px 竖线分隔，分隔本身就是节奏；
 *   - 数值默认无颜色；仅当该指标 tone='warn'/'danger' 且数值 > 0 时染色，
 *     避免「整页都是色块」的视觉过载；
 *   - 窄屏（<560px）水平滚动，保留密度而不折行。
 *
 * Props 设计为「数据驱动」：调用方传一组 items，组件不内置任何业务语义，
 * 便于在「概览」「租户管理」「成员管理」等场景复用。
 */

defineProps<{
  items: ReadonlyArray<{
    /** 指标标签（短，4-8 个汉字） */
    label: string
    /** 指标数值；null 表示加载中骨架 */
    value: number | string | null
    /** 可选辅助说明（数值右侧的小灰字，如「30 天内」） */
    hint?: string
    /** 语义色调：默认 plain；warn / danger 仅在 value > 0 时染色 */
    tone?: 'plain' | 'warn' | 'danger'
    /** 可选点击：传入 router push 目标路径或 onClick 回调 */
    to?: string
    onClick?: () => void
  }>
  /** 加载中：所有数值显示为骨架占位 */
  loading?: boolean
}>()

function formatValue(v: number | string | null): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'number') return v.toLocaleString('zh-CN')
  return v
}
</script>

<template>
  <section class="metric-strip" :aria-busy="loading || undefined">
    <component
      v-for="(item, idx) in items"
      :is="item.to ? 'router-link' : (item.onClick ? 'button' : 'div')"
      :key="`${item.label}-${idx}`"
      :to="item.to"
      :type="item.onClick ? 'button' : undefined"
      class="metric"
      :class="[
        item.tone === 'warn' && typeof item.value === 'number' && item.value > 0 ? 'is-warn' : '',
        item.tone === 'danger' && typeof item.value === 'number' && item.value > 0 ? 'is-danger' : '',
        (item.to || item.onClick) ? 'is-link' : '',
      ]"
      @click="item.onClick"
    >
      <span class="metric-label">{{ item.label }}</span>
      <span class="metric-row">
        <span v-if="loading" class="metric-skel" aria-hidden="true" />
        <span v-else class="metric-value">{{ formatValue(item.value) }}</span>
        <span v-if="item.hint" class="metric-hint">{{ item.hint }}</span>
      </span>
    </component>
  </section>
</template>

<style scoped>
/* 整条带：单行、滚动条出现在容器自身；不给外部裁剪余地 */
.metric-strip {
  display: flex;
  align-items: stretch;
  gap: 0;
  padding: 4px 0;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  scrollbar-width: thin;
  /* 不允许子项再加任何卡片样式；让分隔线本身承担节奏 */
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 140px;
  flex: 1 1 0;
  padding: 14px 20px;
  text-align: left;
  appearance: none;
  background: transparent;
  border: 0;
  border-right: 1px solid var(--border);
  color: inherit;
  font: inherit;
  text-decoration: none;
}
.metric:last-child { border-right: 0; }

.metric-label {
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--text-muted);
  /* 单行不溢出 */
  white-space: nowrap;
}
.metric-row {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}
.metric-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.01em;
  color: var(--text);
  /* 关键：等宽数字让多列数值对齐 */
  font-variant-numeric: tabular-nums;
}
.metric-hint {
  font-size: 11.5px;
  color: var(--text-muted);
  letter-spacing: 0.02em;
}

/* 语义色调：仅当 value > 0 时染色，避免「全是色块」的视觉过载 */
.metric.is-warn .metric-value { color: #b45309; }
.metric.is-danger .metric-value { color: var(--danger); }
[data-theme="dark"] .metric.is-warn .metric-value { color: #d97706; }

/* 可点击的指标项：hover 时只改色，不改背景，保持「印刷品」气质 */
.metric.is-link {
  cursor: pointer;
  transition: background 0.15s ease;
}
.metric.is-link:hover { background: var(--surface-elevated); }
.metric.is-link:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

/* 加载骨架 */
.metric-skel {
  display: inline-block;
  width: 56px;
  height: 24px;
  border-radius: 4px;
  background: var(--surface-elevated);
  animation: skel 1.4s ease-in-out infinite;
}
@keyframes skel {
  0%, 100% { opacity: 0.55; }
  50% { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .metric-skel { animation: none; opacity: 0.7; }
  .metric.is-link { transition: none; }
}

/* 窄屏：减小内边距，允许水平滚动以保留密度而不折行 */
@media (max-width: 720px) {
  .metric { min-width: 120px; padding: 12px 16px; }
  .metric-value { font-size: 22px; }
}
</style>
