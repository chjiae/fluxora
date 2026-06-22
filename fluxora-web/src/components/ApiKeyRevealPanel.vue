<script setup lang="ts">
/**
 * ApiKeyRevealPanel — 一次性 API Key 展示组件。
 *
 * 安全约束（与 AGENT.md 完整 Key 安全规则对齐）：
 *
 *   1. 仅在创建 API 的 *本次响应* 中展示一次完整 plaintext；
 *   2. 强制使用 `:closable="false" :mask-closable="false"`，用户必须主动点击
 *      「我已妥善保存」按钮才能关闭；
 *   3. 关闭后立刻 emit('close')，父组件应同步把 plaintext 从所有 ref 中置空；
 *   4. 不写入 localStorage / sessionStorage / URL / cookie；不渲染到 <input>
 *      （防止浏览器 autofill 拦截）；
 *   5. 不在控制台 / 日志 / Toast 中复述完整 Key；
 *   6. 复制使用 navigator.clipboard.writeText（带 execCommand fallback）。
 */
import { ref } from 'vue'
import { AlertTriangle, Check, Copy } from 'lucide-vue-next'

const props = defineProps<{
  show: boolean
  plaintext: string
  /** Key 前缀，用作弹窗副标题；不含密钥段任何字节 */
  keyPrefix: string
  /** Key 显示名称 */
  name: string
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

const copied = ref(false)
const copyError = ref(false)

async function copyKey() {
  copied.value = false
  copyError.value = false
  // 优先现代 clipboard API；失败则回退到 execCommand
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(props.plaintext)
    } else {
      const ta = document.createElement('textarea')
      ta.value = props.plaintext
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (!ok) throw new Error('fallback copy failed')
    }
    copied.value = true
    // 2 秒后清掉复制提示，但不影响 plaintext 显示
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    copyError.value = true
  }
}

function confirmClose() {
  copied.value = false
  copyError.value = false
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
    class="key-reveal-modal"
    style="width: min(560px, calc(100vw - 32px))"
  >
    <template #header>
      <div class="reveal-head">
        <h2>API Key 已创建</h2>
        <span class="reveal-head-code">{{ name }} · {{ keyPrefix }}</span>
      </div>
    </template>

    <div class="reveal-body">
      <!-- 强调一次性安全提示 -->
      <div class="reveal-warn">
        <n-icon :size="18"><AlertTriangle /></n-icon>
        <span>该 API Key 仅展示一次，关闭后无法再次查看，请立即妥善保存。</span>
      </div>

      <!-- 完整 Key 展示区：monospace + 大字号 + 可选中 -->
      <div class="reveal-key">
        <code class="reveal-key-text">{{ plaintext }}</code>
      </div>

      <!-- 复制按钮 + 复制反馈 -->
      <div class="reveal-actions">
        <n-button
          v-if="!copied"
          type="primary"
          ghost
          @click="copyKey"
          :class="{ 'copy-error': copyError }"
        >
          <template #icon>
            <n-icon><Copy /></n-icon>
          </template>
          复制 Key
        </n-button>
        <n-button v-else type="success" ghost disabled>
          <template #icon>
            <n-icon><Check /></n-icon>
          </template>
          已复制
        </n-button>
        <span v-if="copyError" class="reveal-copy-error">复制失败，请手动选中文本复制</span>
      </div>

      <!-- 使用建议 -->
      <ul class="reveal-tips">
        <li>建议立即粘贴到密钥管理工具（如 1Password、Bitwarden）或 CI/CD 环境变量中。</li>
        <li>关闭弹窗后该 Key 仅以 <code>{{ keyPrefix }}...</code> 前缀形式出现在列表中。</li>
        <li>如不慎丢失，请删除该 Key 并创建新的。</li>
      </ul>
    </div>

    <template #footer>
      <div class="reveal-foot">
        <n-button type="primary" @click="confirmClose">我已妥善保存</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
:deep(.key-reveal-modal) { border-radius: 14px; }
.reveal-head h2 {
  margin: 0; font-size: 17px; font-weight: 650; letter-spacing: -0.005em;
}
.reveal-head-code {
  display: block; margin-top: 4px;
  font-family: var(--font-mono), monospace;
  font-size: 12px; color: var(--text-muted);
}
.reveal-body { display: flex; flex-direction: column; gap: 16px; padding-top: 4px; }

.reveal-warn {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--danger) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--danger) 30%, var(--border));
  border-radius: 10px;
  font-size: 13px;
  color: var(--danger);
}
[data-theme="dark"] .reveal-warn {
  background: color-mix(in srgb, var(--danger) 12%, transparent);
}

.reveal-key {
  padding: 14px 16px;
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow-x: auto;
}
.reveal-key-text {
  display: block;
  font-family: var(--font-mono), monospace;
  font-size: 14.5px;
  line-height: 1.5;
  color: var(--text);
  word-break: break-all;
  user-select: all;
}

.reveal-actions {
  display: flex; align-items: center; gap: 12px;
}
.reveal-copy-error {
  font-size: 12.5px;
  color: var(--danger);
}

.reveal-tips {
  margin: 0; padding: 0 0 0 18px;
  font-size: 12.5px; color: var(--text-muted); line-height: 1.7;
}
.reveal-tips code {
  font-family: var(--font-mono), monospace;
  font-size: 12px;
  color: var(--text);
}

.reveal-foot { display: flex; justify-content: flex-end; }
</style>
