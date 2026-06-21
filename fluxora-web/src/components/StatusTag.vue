<script setup lang="ts">
import { NTag, type TagProps } from 'naive-ui'
import { computed } from 'vue'

type StatusCategory = 'tenant' | 'status'

const props = defineProps<{
  /** 状态所属业务域，避免租户类型与可用状态使用相同字符串时产生歧义。 */
  category: StatusCategory
  /** 后端枚举值，仅在组件内映射为用户可读中文。 */
  value: string
}>()

const tenantLabels: Record<string, string> = {
  SELF_OPERATED: '自营',
  STANDARD: '标准',
}
const statusLabels: Record<string, string> = {
  ENABLED: '已启用',
  DISABLED: '已停用',
  EXPIRED: '已过期',
  DELETED: '已删除',
}
const statusTypes: Record<string, NonNullable<TagProps['type']>> = {
  ENABLED: 'success',
  DISABLED: 'warning',
  EXPIRED: 'error',
  DELETED: 'default',
}

const label = computed(() => {
  const labels = props.category === 'tenant' ? tenantLabels : statusLabels
  return labels[props.value] ?? '未知状态'
})
const tagType = computed<NonNullable<TagProps['type']>>(() => {
  if (props.category === 'tenant') return props.value === 'SELF_OPERATED' ? 'info' : 'default'
  return statusTypes[props.value] ?? 'default'
})
</script>

<template>
  <n-tag :type="tagType" size="small" round>
    {{ label }}
  </n-tag>
</template>
