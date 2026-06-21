<script setup lang="ts">
import { ArrowUpRight, Check, Menu, X, Sun, Moon } from 'lucide-vue-next'
import { ref } from 'vue'
import { useThemeStore } from '@/stores/theme'
const open = ref(false)
const themeStore = useThemeStore()
</script>
<template>
  <div class="public-shell">
    <header class="header"><RouterLink class="brand" to="/">fluxora<span>.</span></RouterLink><button class="mobile" @click="open=!open"><X v-if="open"/><Menu v-else/></button><nav :class="{open}"><a href="#product">产品介绍</a><a href="#advantages">产品优势</a><RouterLink to="/docs">文档</RouterLink><a href="#faq">FAQ</a><RouterLink class="console-link" to="/console">进入控制台 <ArrowUpRight :size="15"/></RouterLink><button class="theme-toggle" :aria-label="themeStore.theme === 'dark' ? '切换亮色' : '切换暗色'" @click="themeStore.toggle()"><Sun v-if="themeStore.theme === 'dark'" :size="16"/><Moon v-else :size="16"/></button></nav></header>
    <main>
      <section class="hero" id="product"><p class="eyebrow">API OPERATIONS, WITHOUT THE FRICTION</p><h1>把模型调用的复杂性，<br><em>留在</em> Fluxora 之后。</h1><p class="lede">面向开发者与运营团队的多租户 API 中转平台。统一接入、稳定流式响应、清晰的运行边界。</p><div class="actions"><RouterLink to="/docs" class="button primary">开始接入 <ArrowUpRight :size="17"/></RouterLink><a href="#advantages" class="button">了解能力</a></div><div class="signal"><span></span>运行时基础骨架 · 当前阶段</div></section>
      <section class="statement"><p>从一个 API 入口出发，组织多上游、多协议与多租户的调用秩序。</p></section>
      <section class="capabilities" id="advantages"><article><b>01</b><h2>API 中转</h2><p>为后续请求鉴权、路由与上游选择预留清晰的数据面边界。</p></article><article><b>02</b><h2>流式接口</h2><p>网关以独立 Vert.x 进程运行，为 SSE 中继保留高性能起点。</p></article><article><b>03</b><h2>多租户</h2><p>控制面与数据面分离，使平台运营和租户运营能够自然扩展。</p></article></section>
      <section class="onboard"><div><p class="eyebrow">DEVELOPER FIRST</p><h2>从文档到首个请求，路径保持短而清楚。</h2></div><ol><li><Check/>阅读接入文档</li><li><Check/>创建项目与 API Key</li><li><Check/>在熟悉的客户端中调用</li></ol><RouterLink to="/docs" class="text-link">查看文档 <ArrowUpRight :size="17"/></RouterLink></section>
      <section class="faq" id="faq"><p class="eyebrow">FAQ</p><h2>常见问题</h2><details open><summary>Fluxora 当前可以用于生产请求吗？</summary><p>本轮仅交付前后端基础骨架与静态体验，尚未实现真实中继、鉴权和计费。</p></details><details><summary>后续是否支持不同模型协议？</summary><p>是。平台边界已为多上游、多协议和路由能力保留扩展空间。</p></details><details><summary>我可以先查看控制台吗？</summary><p>可以。控制台当前使用 Mock 状态，用于展示未来的操作结构。</p></details></section>
    </main><footer><span>© 2026 Fluxora</span><span>开发者基础设施 · 公开预览</span></footer>
  </div>
</template>

<style scoped>
.public-shell { min-height: 100dvh; background: var(--bg); }
.header { max-width: 1200px; height: 64px; margin: auto; padding: 0 32px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border); }
.brand { font-size: 22px; font-weight: 700; letter-spacing: -1.2px; }
.brand span { color: var(--accent); }
.header nav { display: flex; align-items: center; gap: 24px; font-size: 14px; color: var(--text-muted); }
.header nav a:hover { color: var(--text); }
.console-link, .actions, .onboard li, .text-link { display: inline-flex; align-items: center; gap: 6px; }
.theme-toggle { display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; padding: 0; color: var(--text-muted); background: var(--surface); border: 1px solid var(--border); border-radius: 8px; cursor: pointer; }
.mobile { display: none; }
.hero, .capabilities, .faq { max-width: 1200px; margin: auto; padding-inline: 32px; }
.hero { padding-block: 96px; }
.eyebrow { margin: 0; font-size: 12px; letter-spacing: .08em; color: var(--text-muted); }
.hero h1 { max-width: 780px; margin: 16px 0 24px; font-size: clamp(2.5rem, 6vw, 5.5rem); line-height: 1.05; letter-spacing: -.04em; }
.hero em { font-family: Georgia, serif; font-weight: 400; }
.lede { max-width: 540px; color: var(--text-muted); line-height: 1.7; }
.actions { margin-top: 32px; flex-wrap: wrap; }
.button { padding: 10px 16px; border: 1px solid var(--border); border-radius: 6px; }
.primary { color: var(--bg); background: var(--text); border-color: var(--text); }
.signal { margin-top: 64px; font-size: 12px; color: var(--text-muted); }
.signal span { display: inline-block; width: 7px; height: 7px; margin-right: 8px; background: var(--accent); border-radius: 50%; }
.statement { padding: 64px 32px; border-block: 1px solid var(--border); }
.statement p { max-width: 760px; margin: auto; font-size: clamp(1.5rem, 3.5vw, 2.5rem); line-height: 1.25; }
.capabilities { display: grid; grid-template-columns: repeat(3, 1fr); padding-block: 48px 88px; }
.capabilities article { min-height: 180px; padding: 24px; border-left: 1px solid var(--border); }
.capabilities h2 { margin: 8px 0; font-size: 18px; }
.capabilities p, .faq p { color: var(--text-muted); line-height: 1.7; }
.onboard { padding: 72px max(32px, calc((100% - 1136px) / 2)); color: var(--bg); background: var(--text); }
.onboard h2 { max-width: 560px; margin: 12px 0 24px; font-size: 28px; }
.onboard ol { margin: 0 0 28px; padding-left: 20px; line-height: 1.9; }
.onboard li svg { color: var(--accent); }
.faq { max-width: 820px; padding-block: 72px; }
.faq h2 { margin: 12px 0 20px; }
.faq details { padding: 18px 0; border-bottom: 1px solid var(--border); }
.faq summary { cursor: pointer; font-weight: 600; }
.faq details p { margin: 10px 0 0; }
footer { display: flex; justify-content: space-between; gap: 16px; padding: 24px 32px; font-size: 13px; color: var(--text-muted); border-top: 1px solid var(--border); }
@media (max-width: 720px) { .header { padding: 0 20px; } .mobile { display: inline-flex; } .header nav { display: none; } .header nav.open { position: absolute; top: 64px; right: 20px; z-index: 1; display: flex; flex-direction: column; align-items: stretch; padding: 16px; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; } .hero, .capabilities, .faq { padding-inline: 20px; } .hero { padding-block: 64px; } .capabilities { grid-template-columns: 1fr; } .capabilities article { border-top: 1px solid var(--border); border-left: 0; padding-inline: 0; } .onboard { padding: 56px 20px; } footer { flex-direction: column; padding-inline: 20px; } }
</style>
