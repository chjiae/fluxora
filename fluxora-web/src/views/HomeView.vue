<script setup lang="ts">
import { ArrowUpRight, Check, Menu, X } from 'lucide-vue-next'
import { ref } from 'vue'
const open = ref(false)
</script>
<template>
  <div class="public-shell">
    <header class="header"><RouterLink class="brand" to="/">fluxora<span>.</span></RouterLink><button class="mobile" @click="open=!open"><X v-if="open"/><Menu v-else/></button><nav :class="{open}"><a href="#product">产品介绍</a><a href="#advantages">产品优势</a><RouterLink to="/docs">文档</RouterLink><a href="#faq">FAQ</a><RouterLink class="console-link" to="/console">进入控制台 <ArrowUpRight :size="15"/></RouterLink></nav></header>
    <main>
      <section class="hero" id="product"><p class="eyebrow">API OPERATIONS, WITHOUT THE FRICTION</p><h1>把模型调用的复杂性，<br><em>留在</em> Fluxora 之后。</h1><p class="lede">面向开发者与运营团队的多租户 API 中转平台。统一接入、稳定流式响应、清晰的运行边界。</p><div class="actions"><RouterLink to="/docs" class="button primary">开始接入 <ArrowUpRight :size="17"/></RouterLink><a href="#advantages" class="button">了解能力</a></div><div class="signal"><span></span>运行时基础骨架 · 当前阶段</div></section>
      <section class="statement"><p>从一个 API 入口出发，组织多上游、多协议与多租户的调用秩序。</p></section>
      <section class="capabilities" id="advantages"><article><b>01</b><h2>API 中转</h2><p>为后续请求鉴权、路由与上游选择预留清晰的数据面边界。</p></article><article><b>02</b><h2>流式接口</h2><p>网关以独立 Vert.x 进程运行，为 SSE 中继保留高性能起点。</p></article><article><b>03</b><h2>多租户</h2><p>控制面与数据面分离，使平台运营和租户运营能够自然扩展。</p></article></section>
      <section class="onboard"><div><p class="eyebrow">DEVELOPER FIRST</p><h2>从文档到首个请求，路径保持短而清楚。</h2></div><ol><li><Check/>阅读接入文档</li><li><Check/>创建项目与 API Key</li><li><Check/>在熟悉的客户端中调用</li></ol><RouterLink to="/docs" class="text-link">查看文档 <ArrowUpRight :size="17"/></RouterLink></section>
      <section class="faq" id="faq"><p class="eyebrow">FAQ</p><h2>常见问题</h2><details open><summary>Fluxora 当前可以用于生产请求吗？</summary><p>本轮仅交付前后端基础骨架与静态体验，尚未实现真实中继、鉴权和计费。</p></details><details><summary>后续是否支持不同模型协议？</summary><p>是。平台边界已为多上游、多协议和路由能力保留扩展空间。</p></details><details><summary>我可以先查看控制台吗？</summary><p>可以。控制台当前使用 Mock 状态，用于展示未来的操作结构。</p></details></section>
    </main><footer><span>© 2026 Fluxora</span><span>开发者基础设施 · 公开预览</span></footer>
  </div>
</template>
