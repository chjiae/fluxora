<script setup lang="ts">
import { NAlert, NButton, NEmpty, NSpin } from 'naive-ui'

type AsyncStateType = 'loading' | 'empty' | 'error'
type AsyncErrorKey = 'network' | 'timeout' | 'service'

defineOptions({ inheritAttrs: false })

const props = withDefaults(defineProps<{
  state: AsyncStateType
  /** 仅允许使用预定义安全文案键，禁止传入后端或运行时原始错误文本。 */
  errorKey?: AsyncErrorKey
  emptyDescription?: string
}>(), {
  emptyDescription: '暂无数据',
  errorKey: 'service',
})

const emit = defineEmits<{ retry: [] }>()

/**
 * 用户可见错误必须来自受控白名单，避免接口、数据库或运行时错误被渲染到页面。
 */
const errorMessages: Record<AsyncErrorKey, string> = {
  network: '网络连接失败，请检查网络后重试',
  timeout: '请求超时，请稍后重试',
  service: '服务暂时不可用，请稍后重试',
}
</script>

<template>
  <div class="async-state" role="status" aria-live="polite">
    <n-spin v-if="state === 'loading'" size="small">
      正在加载，请稍候
    </n-spin>
    <n-empty v-else-if="state === 'empty'" :description="emptyDescription" />
    <div v-else class="async-state__error">
      <n-alert type="error" :show-icon="true" title="加载失败">
        {{ errorMessages[props.errorKey] }}
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
