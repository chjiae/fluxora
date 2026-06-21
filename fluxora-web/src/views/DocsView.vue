<script setup lang="ts">
import { Menu, X, Sun, Moon, ArrowUpRight } from 'lucide-vue-next'; import { ref } from 'vue'; import { useThemeStore } from '@/stores/theme'
const open=ref(false); const themeStore=useThemeStore(); const sections=['平台接入说明','API Key 使用说明','OpenAI 协议接入','Anthropic 协议接入','客户端与 CLI','常见问题']; const current=ref(sections[0])
const navOpen=ref(false)
</script>
<template><div class="docs"><header class="header"><RouterLink class="brand" to="/">fluxora<span>.</span></RouterLink><button class="mobile" @click="navOpen=!navOpen"><X v-if="navOpen"/><Menu v-else/></button><nav :class="{open:navOpen}"><a href="/#product">产品介绍</a><a href="/#advantages">产品优势</a><RouterLink to="/docs">文档</RouterLink><a href="/#faq">FAQ</a><RouterLink to="/console" class="console-link">进入控制台 <ArrowUpRight :size="15"/></RouterLink><button class="theme-toggle" :aria-label="themeStore.theme==='dark'?'切换亮色':'切换暗色'" @click="themeStore.toggle()"><Sun v-if="themeStore.theme==='dark'" :size="16"/><Moon v-else :size="16"/></button></nav></header><button class="toc-button" @click="open=!open"><Menu v-if="!open"/><X v-else/>目录</button><aside :class="{open}"><p>文档</p><button v-for="item in sections" :class="{active:current===item}" @click="current=item">{{item}}</button></aside><article><p class="eyebrow">GUIDES / {{ current }}</p><h1>{{current}}</h1><p class="lead">这是 Fluxora 文档系统的最小骨架。它以稳定的阅读节奏呈现后续接入、鉴权与协议说明。</p><h2>开始之前</h2><p>准备一个项目与 API Key。真实创建、权限校验和调用配置将在后续阶段接入。</p><pre><code>curl https://api.fluxora.example/v1/models \\
  -H "Authorization: Bearer $FLUXORA_API_KEY"</code></pre><h2>设计原则</h2><p>一个入口、一致的调用体验，以及可追溯的运行边界。当前文本为 Mock 内容，不会触发真实请求。</p></article></div></template>

<style scoped>
.docs { display: grid; grid-template: 64px 1fr / 220px minmax(0, 1fr); min-height: 100dvh; max-width: 1200px; margin: auto; }
.header { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; padding: 0 32px; border-bottom: 1px solid var(--border); }
.brand { font-size: 22px; font-weight: 700; letter-spacing: -1.2px; }
.brand span { color: var(--accent); }
.header nav { display: flex; align-items: center; gap: 24px; font-size: 14px; color: var(--text-muted); }
.header nav a:hover { color: var(--text); }
.console-link { display: inline-flex; align-items: center; gap: 5px; }
.theme-toggle, .mobile { display: inline-flex; align-items: center; justify-content: center; padding: 0; color: var(--text-muted); background: transparent; border: 0; cursor: pointer; }
.mobile, .toc-button { display: none; }
aside { padding: 28px 16px; border-right: 1px solid var(--border); }
aside p { margin: 0 8px 12px; font-size: 13px; font-weight: 600; }
aside button { display: block; width: 100%; padding: 8px 10px; color: var(--text-muted); text-align: left; background: transparent; border: 0; border-radius: 6px; cursor: pointer; }
aside button:hover, aside button.active { color: var(--text); background: var(--surface-elevated); }
article { max-width: 800px; padding: 40px; }
.eyebrow { color: var(--text-muted); font-size: 12px; letter-spacing: .08em; }
h1 { margin: 12px 0; font-size: 32px; }
h2 { margin: 36px 0 12px; font-size: 22px; }
.lead, article > p { color: var(--text-muted); line-height: 1.7; }
pre { padding: 16px; overflow-x: auto; background: var(--surface-elevated); border: 1px solid var(--border); border-radius: 8px; }
@media (max-width: 720px) { .docs { display: block; } .header { height: 64px; padding: 0 20px; } .mobile, .toc-button { display: inline-flex; } .header nav { display: none; } .header nav.open { position: absolute; top: 64px; right: 20px; z-index: 1; display: flex; flex-direction: column; align-items: stretch; padding: 16px; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; } .toc-button { margin: 16px 20px 0; gap: 6px; background: transparent; border: 0; } aside { display: none; border: 0; } aside.open { display: block; padding: 16px 20px; border-bottom: 1px solid var(--border); } article { padding: 28px 20px; } }
</style>
