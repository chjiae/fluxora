<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { importCredentials, type CredentialImportResult, type CredentialImportItemResult, type CredentialAuthType } from '@/services/upstream'

const props = defineProps<{ show: boolean; channelId: number; protocol?: 'OPENAI' | 'ANTHROPIC' }>()
const emit = defineEmits<{ 'update:show': [boolean]; done: [] }>()

const message = useMessage()
const text = ref('')
const namePrefix = ref('')
const priority = ref(100)
const weight = ref(100)
const remark = ref('')
const authType = ref<CredentialAuthType>(props.protocol === 'ANTHROPIC' ? 'X_API_KEY' : 'BEARER')
const submitting = ref(false)
const result = ref<CredentialImportResult | null>(null)

const lines = computed(() => text.value.split(/\r?\n/))
const nonBlankCount = computed(() => lines.value.filter(l => l.trim()).length)

const RESULT_LABEL: Record<CredentialImportItemResult, string> = {
  IMPORTED: '已导入',
  SKIPPED_BATCH_DUPLICATE: '已跳过',
  SKIPPED_EXISTING: '已跳过',
  INVALID: '未导入',
  OVER_LIMIT: '未导入',
  SKIPPED_CONCURRENT: '已跳过',
}

function clearPlaintext() {
  // 安全：提交完成、取消或关闭后立即清空明文输入与文件内容，不保留在内存或浏览器存储
  text.value = ''
  namePrefix.value = ''
  priority.value = 100
  weight.value = 100
  remark.value = ''
  authType.value = props.protocol === 'ANTHROPIC' ? 'X_API_KEY' : 'BEARER'
}

function close() { emit('update:show', false) }

function handleClose() {
  clearPlaintext()
  result.value = null
  close()
}

function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) { message.warning('文件过大，请限制在 2MB 以内'); input.value = ''; return }
  const reader = new FileReader()
  reader.onload = () => {
    const content = String(reader.result || '')
    text.value = text.value ? text.value + '\n' + content : content
    input.value = ''
  }
  reader.onerror = () => { message.error('读取文件失败，请重试'); input.value = '' }
  reader.readAsText(file)
}

async function submit() {
  if (!nonBlankCount.value) { message.warning('请粘贴或上传至少一条凭证'); return }
  submitting.value = true
  try {
    result.value = await importCredentials({
      providerChannelId: props.channelId,
      lines: lines.value,
      namePrefix: namePrefix.value.trim() || undefined,
      priority: priority.value, weight: weight.value,
      remark: remark.value.trim() || undefined,
      authType: authType.value,
    })
    // 提交完成后立即清空明文输入；结果只保留脱敏明细
    text.value = ''
    message.success(buildToast(result.value))
    emit('done')
  } catch (e: any) { message.error(e.userMessage || '导入失败，请稍后重试') }
  finally { submitting.value = false }
}

function buildToast(r: CredentialImportResult): string {
  const s = r.summary
  if (s.imported === 0) return '未导入任何凭证：提交的凭证均已存在或重复'
  if (s.skippedExisting > 0 || s.skippedBatchDuplicate > 0 || s.invalid > 0 || s.concurrentDuplicate > 0) {
    return `已成功导入 ${s.imported} 条，跳过 ${s.skippedExisting + s.skippedBatchDuplicate + s.concurrentDuplicate} 条，请查看导入明细`
  }
  return `已成功导入 ${s.imported} 条凭证`
}

watch(() => props.show, v => { if (!v) { clearPlaintext(); result.value = null } })
</script>

<template>
  <n-drawer :show="show" :width="560" placement="right" @update:show="v => { if (!v) handleClose() }">
    <n-drawer-content :title="result ? '导入结果' : '批量导入凭证'" closable>
      <template v-if="!result">
        <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
          当前租户内已存在且未软删除的相同凭证会自动跳过；无论原凭证启用或停用，均不会覆盖或自动启用；已软删除凭证视为不存在，可重新导入。
        </n-alert>
        <n-form label-placement="top">
          <n-form-item label="凭证内容（每行一条）">
            <n-input v-model:value="text" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" placeholder="粘贴凭证，一行一个&#10;sk-xxxxx&#10;sk-yyyyy" />
            <template #feedback>已识别 {{ nonBlankCount }} 条非空凭证。</template>
          </n-form-item>
          <n-form-item label="从文件导入">
            <input type="file" accept=".txt,.csv,text/plain,text/csv" @change="onFile" />
          </n-form-item>
          <n-form-item label="认证方式"><n-select v-model:value="authType" :options="[{ label: 'Bearer Token', value: 'BEARER' }, { label: 'x-api-key', value: 'X_API_KEY' }, { label: '无认证', value: 'NONE' }]" /></n-form-item>
          <n-form-item label="统一名称前缀（可选）"><n-input v-model:value="namePrefix" placeholder="留空则自动生成脱敏名称" /></n-form-item>
          <div style="display: flex; gap: 12px">
            <n-form-item label="优先级" style="flex: 1"><n-input-number v-model:value="priority" :min="0" :max="100000" /></n-form-item>
            <n-form-item label="权重" style="flex: 1"><n-input-number v-model:value="weight" :min="1" :max="100000" /></n-form-item>
          </div>
          <n-form-item label="备注（可选）"><n-input v-model:value="remark" /></n-form-item>
        </n-form>
      </template>
      <template v-else>
        <div class="result-summary">
          <div class="summary-row"><span>本次读取</span><strong>{{ result.summary.totalRead }}</strong></div>
          <div class="summary-row"><span>成功导入</span><strong class="ok">{{ result.summary.imported }}</strong></div>
          <div class="summary-row"><span>当前租户已存在</span><strong>{{ result.summary.skippedExisting }}</strong></div>
          <div class="summary-row"><span>同一批次重复</span><strong>{{ result.summary.skippedBatchDuplicate }}</strong></div>
          <div class="summary-row"><span>并发重复</span><strong>{{ result.summary.concurrentDuplicate }}</strong></div>
          <div class="summary-row"><span>格式无效</span><strong>{{ result.summary.invalid }}</strong></div>
        </div>
        <n-data-table :columns="[
          { title: '行号', key: 'lineNumber', width: 70 },
          { title: '凭证标识', key: 'maskedValue', render: (r: any) => r.maskedValue || '——' },
          { title: '结果', key: 'result', width: 90, render: (r: any) => RESULT_LABEL[r.result as CredentialImportItemResult] },
          { title: '原因', key: 'reason' },
        ]" :data="result.items" :pagination="{ pageSize: 20 }" :bordered="false" size="small" style="margin-top: 12px" />
      </template>
      <template #footer>
        <n-button @click="handleClose">关闭</n-button>
        <n-button v-if="!result" type="primary" :loading="submitting" @click="submit">开始导入</n-button>
        <n-button v-else type="primary" @click="handleClose">完成</n-button>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.result-summary { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px 20px; padding: 12px 0; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); }
.summary-row { display: flex; justify-content: space-between; align-items: baseline; font-size: 13px; color: var(--text-muted); }
.summary-row strong { font-size: 18px; color: var(--text); font-variant-numeric: tabular-nums; }
.summary-row strong.ok { color: #20a779; }
</style>
