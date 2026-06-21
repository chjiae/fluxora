<script setup lang="ts">
import { ArrowUpRight, Check } from 'lucide-vue-next'
import { NButton, NCard, NDivider, NTag } from 'naive-ui'

import PublicHeader from '@/components/PublicHeader.vue'

const capabilities = [
  ['01', 'API 中转', '为后续请求鉴权、路由与上游选择预留清晰的数据面边界。'],
  ['02', '流式接口', '网关以独立 Vert.x 进程运行，为 SSE 中继保留高性能起点。'],
  ['03', '多租户', '控制面与数据面分离，使平台运营和租户运营能够自然扩展。'],
]
</script>

<template>
  <div class="public-shell">
    <PublicHeader />
    <main>
      <section id="product" class="hero content-width">
        <n-tag type="info" :bordered="false" size="small">API OPERATIONS, WITHOUT THE FRICTION</n-tag>
        <h1>把模型调用的复杂性，<br><em>留在</em> Fluxora 之后。</h1>
        <p class="lead">面向开发者与运营团队的多租户 API 中转平台。统一接入、稳定流式响应、清晰的运行边界。</p>
        <div class="actions">
          <RouterLink to="/docs"><n-button type="primary" size="large">开始接入<template #icon><ArrowUpRight :size="17" /></template></n-button></RouterLink>
          <a href="#advantages"><n-button size="large">了解能力</n-button></a>
        </div>
        <div class="flow" aria-label="请求流转结构示意">
          <span>客户端请求</span><i>→</i><span>统一入口</span><i>→</i><span>路由边界</span><i>→</i><span>上游协议</span>
        </div>
      </section>

      <section class="statement"><p>从一个 API 入口出发，组织多上游、多协议与多租户的调用秩序。</p></section>

      <section id="advantages" class="content-width capability-section">
        <div class="section-heading"><n-tag type="info" :bordered="false" size="small">平台能力</n-tag><h2>为稳定运营而设计的基础骨架</h2></div>
        <div class="capabilities">
          <n-card v-for="([index, title, description]) in capabilities" :key="index" class="capability-card" size="large">
            <span class="index">{{ index }}</span><h3>{{ title }}</h3><p>{{ description }}</p>
          </n-card>
        </div>
      </section>

      <section class="onboarding"><div class="content-width onboarding-grid"><div><n-tag type="info" :bordered="false" size="small">DEVELOPER FIRST</n-tag><h2>从文档到首个请求，路径保持短而清楚。</h2></div><div><ol><li><Check :size="17" />阅读接入文档</li><li><Check :size="17" />创建项目与 API Key</li><li><Check :size="17" />在熟悉的客户端中调用</li></ol><RouterLink to="/docs"><n-button type="primary">查看文档<template #icon><ArrowUpRight :size="17" /></template></n-button></RouterLink></div></div></section>

      <section id="faq" class="faq content-width"><n-tag type="info" :bordered="false" size="small">FAQ</n-tag><h2>常见问题</h2><n-card size="small"><details open><summary>Fluxora 当前可以用于生产请求吗？</summary><p>本轮仅交付前后端基础骨架与静态体验，尚未实现真实中继、鉴权和计费。</p></details><n-divider /><details><summary>后续是否支持不同模型协议？</summary><p>是。平台边界已为多上游、多协议和路由能力保留扩展空间。</p></details><n-divider /><details><summary>我可以先查看控制台吗？</summary><p>可以。控制台当前使用 Mock 状态，用于展示未来的操作结构。</p></details></n-card></section>
    </main>
    <footer><span>© 2026 Fluxora</span><span>开发者基础设施 · 公开预览</span></footer>
  </div>
</template>

<style scoped>
.public-shell { min-height: 100dvh; background: var(--bg); }.content-width { width: min(1120px, calc(100% - 64px)); margin: auto; }.hero { padding-block: clamp(72px, 11vw, 142px) 92px; }.hero h1 { max-width: 800px; margin: 20px 0 22px; font-size: clamp(2.7rem, 6.3vw, 5.9rem); line-height: 1.02; letter-spacing: -.055em; }.hero em { font-family: Georgia, serif; font-weight: 400; }.lead { max-width: 560px; color: var(--text-muted); line-height: 1.8; }.actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 32px; }.flow { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 72px; padding: 18px 20px; border: 1px solid var(--border); border-radius: 4px; color: var(--text-muted); font: 13px var(--font-mono); }.flow span { color: var(--text); }.flow i { color: #3b82f6; font-style: normal; }.statement { padding: 64px 32px; border-block: 1px solid var(--border); }.statement p { max-width: 800px; margin: auto; font-size: clamp(1.55rem, 3.4vw, 2.65rem); line-height: 1.3; }.capability-section,.faq { padding-block: 88px; }.section-heading h2,.faq h2 { margin: 14px 0 30px; font-size: clamp(1.8rem, 3vw, 2.5rem); }.capabilities { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }.capability-card { min-height: 210px; }.index { color: #3b82f6; font: 600 13px var(--font-mono); }.capability-card h3 { margin: 34px 0 10px; font-size: 20px; }.capability-card p,.faq p { color: var(--text-muted); line-height: 1.75; }.onboarding { padding: 76px 0; background: var(--surface-elevated); border-block: 1px solid var(--border); }.onboarding-grid { display: grid; grid-template-columns: 1.15fr .85fr; gap: 64px; }.onboarding h2 { max-width: 540px; margin: 14px 0 0; font-size: clamp(1.8rem, 3vw, 2.6rem); }.onboarding ol { display: grid; gap: 14px; margin: 0 0 26px; padding: 0; list-style: none; }.onboarding li { display: flex; gap: 10px; align-items: center; }.onboarding li svg { color: #3b82f6; }.faq { max-width: 820px; }.faq summary { cursor: pointer; font-weight: 650; } footer { display: flex; justify-content: space-between; gap: 16px; padding: 26px clamp(20px, 4vw, 48px); border-top: 1px solid var(--border); color: var(--text-muted); font-size: 13px; } @media (max-width: 900px) { .content-width { width: min(100% - 40px, 1120px); }.capabilities,.onboarding-grid { grid-template-columns: 1fr; }.onboarding-grid { gap: 30px; }.hero { padding-block: 76px 64px; }.flow { margin-top: 52px; } } @media (max-width: 390px) { .hero h1 { font-size: 2.6rem; }.flow { gap: 8px; padding: 14px; }.statement { padding-inline: 20px; } footer { flex-direction: column; } }
</style>
