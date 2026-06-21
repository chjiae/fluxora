<script setup lang="ts">
import { Menu } from 'lucide-vue-next'
import { NButton, NCard, NDrawer, NDrawerContent, NLayout, NLayoutContent, NLayoutSider } from 'naive-ui'
import { ref } from 'vue'

import PublicHeader from '@/components/PublicHeader.vue'

const sections = ['平台接入说明', 'API Key 使用说明', 'OpenAI 协议接入', 'Anthropic 协议接入', '客户端与 CLI', '常见问题']
const current = ref(sections[0])
const tocOpen = ref(false)

function selectSection(section: string) { current.value = section; tocOpen.value = false }
</script>

<template>
  <div class="docs-shell">
    <PublicHeader />
    <n-layout class="docs-layout" has-sider>
      <n-layout-sider class="desktop-toc" bordered :width="248" content-style="padding: 24px 12px">
        <p class="toc-label">文档目录</p>
        <n-button v-for="section in sections" :key="section" text block class="toc-item" :class="{ active: current === section }" @click="selectSection(section)">{{ section }}</n-button>
      </n-layout-sider>
      <n-layout-content content-style="min-height: calc(100dvh - 64px)"><article class="document"><p class="eyebrow">GUIDES / {{ current }}</p><h1>{{ current }}</h1><p class="lead">这是 Fluxora 文档系统的最小骨架。它以稳定的阅读节奏呈现后续接入、鉴权与协议说明。</p><n-card size="small" class="notice">当前内容为 Mock 文档，不会触发真实请求。</n-card><h2>开始之前</h2><p>准备一个项目与 API Key。真实创建、权限校验和调用配置将在后续阶段接入。</p><pre><code>curl https://api.fluxora.example/v1/models \
  -H "Authorization: Bearer $FLUXORA_API_KEY"</code></pre><h2>设计原则</h2><p>一个入口、一致的调用体验，以及可追溯的运行边界。当前文本为 Mock 内容，不会触发真实请求。</p></article></n-layout-content>
    </n-layout>
    <n-button class="toc-trigger" secondary @click="tocOpen = true"><template #icon><Menu :size="18" /></template>目录</n-button>
    <n-drawer v-model:show="tocOpen" placement="left" :width="288"><n-drawer-content title="文档目录" closable><nav class="drawer-toc" aria-label="文档目录"><n-button v-for="section in sections" :key="section" text block :class="{ active: current === section }" @click="selectSection(section)">{{ section }}</n-button></nav></n-drawer-content></n-drawer>
  </div>
</template>

<style scoped>
.docs-shell { min-height: 100dvh; background: var(--bg); }.docs-layout { max-width: 1200px; min-height: calc(100dvh - 64px); margin: auto; }.toc-label { margin: 0 10px 12px; color: var(--text-muted); font-size: 12px; font-weight: 700; letter-spacing: .08em; }.toc-item,.drawer-toc :deep(.n-button) { justify-content: flex-start; margin-bottom: 4px; padding-inline: 10px; color: var(--text-muted); text-align: left; }.toc-item.active,.drawer-toc :deep(.n-button.active) { color: #2563eb; background: var(--surface-elevated); }.document { max-width: 800px; padding: 56px clamp(28px, 5vw, 72px); }.eyebrow { margin: 0; color: #3b82f6; font: 12px var(--font-mono); letter-spacing: .08em; }.document h1 { margin: 14px 0; font-size: clamp(2rem, 4vw, 3rem); letter-spacing: -.035em; }.document h2 { margin: 42px 0 12px; font-size: 22px; }.lead,.document > p { color: var(--text-muted); line-height: 1.8; }.notice { margin: 28px 0; color: var(--text-muted); }.document pre { padding: 18px; overflow-x: auto; color: #dbeafe; background: #172033; border: 1px solid #334155; border-radius: 6px; }.toc-trigger { display: none; position: fixed; right: 20px; bottom: 20px; z-index: 3; }.drawer-toc { display: grid; gap: 5px; } @media (max-width: 900px) { .desktop-toc { display: none; }.toc-trigger { display: inline-flex; }.document { padding: 44px 28px 88px; } } @media (max-width: 390px) { .document { padding-inline: 20px; }.document pre { margin-inline: 0; padding: 14px; } }
</style>
