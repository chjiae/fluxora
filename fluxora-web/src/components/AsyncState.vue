<script setup lang="ts">
import { NAlert, NButton, NEmpty, NSpin } from 'naive-ui'
import { computed } from 'vue'

type AsyncStateType = 'loading' | 'empty' | 'error'

const props = withDefaults(defineProps<{
  state: AsyncStateType
  /** 调用方传入的、已完成安全映射的中文提示；技术文本会被降级为通用提示。 */
  description?: string
  emptyDescription?: string
}>(), {
  emptyDescription: '暂无数据',
})

const emit = defineEmits<{ retry: [] }>()

/** 拦截常见技术错误特征，保证通用状态组件不会将内部信息带到用户界面。 */
function isSafeChineseDescription(value: string): boolean {
  return !/(?:\b(?:error|exception|sql|select|stack|http|status|token|path)\b|\b[1-5]\d{2}\b|[{}<>]|[A-Z_]{3,}|\/)/i.test(value)
}

const errorDescription = computed(() => {
  const description = props.description?.trim()
  return description && isSafeChineseDescription(description)
    ? description
    : '服务暂时不可用，请稍后重试'
})
</script>

<template>
  <div class="async-state" role="status" aria-live="polite">
    <n-spin v-if="state === 'loading'" size="small">
      正在加载，请稍候
    </n-spin>
    <n-empty v-else-if="state === 'empty'" :description="emptyDescription" />
    <div v-else class="async-state__error">
      <n-alert type="error" :show-icon="true" title="加载失败">
        {{ errorDescription }}
      </n-alert>
      <div class="async-state__actions">
        <n-button size="small" type="primary" @click="emit('retry')">
          重试
        </n-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.async-state {
  display: grid;
  min-height: 144px;
  place-items: center;
}

.async-state__error {
  width: min(100%, 480px);
}

.async-state__actions {
  margin-top: 12px;
  text-align: center;
}
</style>
