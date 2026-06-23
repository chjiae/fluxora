<script setup lang="ts">
/**
 * C 端模型目录页：登录后可访问；只展示当前租户已发布且配置完整的模型。
 *
 * 严禁展示：通道、上游模型、候选、映射、路由、权重、优先级、凭证、内部价格版本、deletedAt。
 * 金额一律以字符串呈现，保留尾随零的去除以提升可读性。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import PublicHeader from '@/components/PublicHeader.vue'
import { listPublicModels, type PublicTenantModel } from '@/services/tenantModel'

const route = useRoute()
/** 在控制台布局内渲染时隐藏 PublicHeader，避免双 header */
const insideConsole = computed(() => route.path.startsWith('/console'))

const message = useMessage()
const items = ref<PublicTenantModel[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    items.value = await listPublicModels()
  } catch (e: any) {
    message.error(e.userMessage || '加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/**
 * 金额展示：后端已固定 CNY 8 位小数；前端去掉尾随零提升可读性，
 * 仍以字符串拼接「￥」前缀；缓存价为 null 时显式提示「不支持」而不是 0。
 */
function formatPrice(v: string | null): string {
  if (v == null) return '不支持'
  // 后端格式形如 "1.50000000"；去尾随零仅做展示，禁止参与计算
  const trimmed = v.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
  return `￥${trimmed}`
}

function capabilityChips(m: PublicTenantModel): string[] {
  const out: string[] = []
  if (m.supportsStreaming) out.push('流式')
  if (m.supportsToolCalling) out.push('工具调用')
  if (m.supportsVision) out.push('视觉')
  if (m.supportsCache) out.push('缓存')
  return out
}
</script>

<template>
  <div class="public-page" :class="{ embedded: insideConsole }">
    <PublicHeader v-if="!insideConsole" />
    <main class="container">
      <header class="hero">
        <h1>模型目录</h1>
        <p>当前租户对外提供的可用模型与计费单价。价格以每 100 万 Token 计，币种 CNY。</p>
      </header>

      <section v-if="loading" class="state">正在加载…</section>
      <section v-else-if="items.length === 0" class="state">当前租户暂无可用模型，请联系管理员配置。</section>
      <section v-else class="grid">
        <article v-for="m in items" :key="m.id" class="card">
          <header class="card-hdr">
            <h2>{{ m.displayName }}</h2>
            <code class="code">{{ m.modelCode }}</code>
          </header>
          <p v-if="m.description" class="desc">{{ m.description }}</p>
          <div v-if="capabilityChips(m).length" class="caps">
            <span v-for="c in capabilityChips(m)" :key="c" class="cap">{{ c }}</span>
          </div>
          <dl class="prices">
            <div><dt>输入</dt><dd>{{ formatPrice(m.inputPricePerMillion) }} <span class="unit">/百万 Token</span></dd></div>
            <div><dt>输出</dt><dd>{{ formatPrice(m.outputPricePerMillion) }} <span class="unit">/百万 Token</span></dd></div>
            <div><dt>缓存写入</dt><dd>{{ formatPrice(m.cacheWritePricePerMillion) }} <span v-if="m.cacheWritePricePerMillion != null" class="unit">/百万 Token</span></dd></div>
            <div><dt>缓存读取</dt><dd>{{ formatPrice(m.cacheReadPricePerMillion) }} <span v-if="m.cacheReadPricePerMillion != null" class="unit">/百万 Token</span></dd></div>
          </dl>
        </article>
      </section>
    </main>
  </div>
</template>

<style scoped>
.public-page { min-height: 100dvh; display: flex; flex-direction: column; background: var(--bg); color: var(--text); }
.public-page.embedded { min-height: auto; display: block; background: transparent; }
.container { flex: 1; max-width: 1120px; width: 100%; margin: 0 auto; padding: 32px clamp(20px, 4vw, 48px) 64px; }
.hero h1 { margin: 0 0 6px; font-size: 28px; font-weight: 650; letter-spacing: -0.02em; }
.hero p { margin: 0; color: var(--text-muted); font-size: 14px; max-width: 560px; }
.state { margin-top: 48px; color: var(--text-muted); font-size: 14px; }
.grid { margin-top: 32px; display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
.card { padding: 18px 18px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); display: flex; flex-direction: column; gap: 10px; }
.card-hdr { display: flex; flex-direction: column; gap: 4px; }
.card-hdr h2 { margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -0.01em; }
.code { font-family: 'JetBrains Mono', 'Cascadia Code', monospace; font-size: 12px; color: var(--text-muted); }
.desc { margin: 0; color: var(--text-muted); font-size: 13px; line-height: 1.5; }
.caps { display: flex; flex-wrap: wrap; gap: 4px; }
.cap { display: inline-block; padding: 1px 8px; font-size: 12px; border-radius: 6px; background: var(--surface-elevated); color: var(--text); }
.prices { display: grid; grid-template-columns: max-content 1fr; gap: 4px 16px; margin: 4px 0 0; font-size: 13px; }
.prices > div { display: contents; }
.prices dt { color: var(--text-muted); padding-top: 1px; }
.prices dd { margin: 0; font-variant-numeric: tabular-nums; }
.unit { color: var(--text-muted); font-size: 12px; margin-left: 2px; }
@media (max-width: 720px) {
  .container { padding: 24px 16px 48px; }
  .hero h1 { font-size: 22px; }
}
</style>
