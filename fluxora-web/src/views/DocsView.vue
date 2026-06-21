<script setup lang="ts">
import { Menu, X } from 'lucide-vue-next'; import { ref } from 'vue'
const open=ref(false); const sections=['平台接入说明','API Key 使用说明','OpenAI 协议接入','Anthropic 协议接入','客户端与 CLI','常见问题']; const current=ref(sections[0])
</script>
<template><div class="docs"><header class="header"><RouterLink class="brand" to="/">fluxora<span>.</span></RouterLink><RouterLink to="/console" class="console-link">进入控制台</RouterLink></header><div class="docs-body"><button class="toc-button" @click="open=!open"><Menu v-if="!open"/><X v-else/>目录</button><aside :class="{open}"><p>文档</p><button v-for="item in sections" :class="{active:current===item}" @click="current=item">{{item}}</button></aside><article><p class="eyebrow">GUIDES / {{ current }}</p><h1>{{current}}</h1><p class="lead">这是 Fluxora 文档系统的最小骨架。它以稳定的阅读节奏呈现后续接入、鉴权与协议说明。</p><h2>开始之前</h2><p>准备一个项目与 API Key。真实创建、权限校验和调用配置将在后续阶段接入。</p><pre><code>curl https://api.fluxora.example/v1/models \\
  -H "Authorization: Bearer $FLUXORA_API_KEY"</code></pre><h2>设计原则</h2><p>一个入口、一致的调用体验，以及可追溯的运行边界。当前文本为 Mock 内容，不会触发真实请求。</p></article></div></div></template>
