<script setup lang="ts">
/**
 * 状态点：6-7px 圆点 + 文字内联标签。
 * 不使用彩色背景 Tag，避免密集表格里色块过多；颜色仅作语义点缀。
 */
defineProps<{ status: 'ENABLED' | 'DISABLED' | 'DELETED' }>()
const LABELS: Record<string, string> = { ENABLED: '启用', DISABLED: '停用', DELETED: '已删除' }
function label(s: string) { return LABELS[s] ?? s }
</script>

<template>
  <span class="status-tag" :class="`status-${status.toLowerCase()}`">
    <span class="dot" aria-hidden="true" />
    <span>{{ label(status) }}</span>
  </span>
</template>

<style scoped>
.status-tag { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; flex-shrink: 0; }
.status-enabled { color: #20a779; }
.status-disabled { color: var(--text-muted); }
.status-deleted { color: var(--danger); }
[data-theme="dark"] .status-enabled { color: #34d399; }
</style>
