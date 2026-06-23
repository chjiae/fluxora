<script setup lang="ts">
import { Menu } from 'lucide-vue-next'
import { NButton, NDrawer, NDrawerContent } from 'naive-ui'
import { computed, ref } from 'vue'

import ThemeToggle from '@/components/ThemeToggle.vue'

const mobileMenuOpen = ref(false)
const mobileMenuLabel = computed(() => mobileMenuOpen.value ? '关闭导航菜单' : '打开导航菜单')

function closeMobileMenu() {
  mobileMenuOpen.value = false
}
</script>

<template>
  <header class="public-header">
    <RouterLink class="brand" to="/" aria-label="Fluxora 首页">fluxora<span>.</span></RouterLink>

    <nav class="desktop-navigation" aria-label="公开页面导航">
      <a href="/#product">产品介绍</a>
      <a href="/#advantages">产品优势</a>
      <RouterLink to="/models">模型目录</RouterLink>
      <RouterLink to="/docs">文档</RouterLink>
      <a href="/#faq">FAQ</a>
      <RouterLink class="console-entry" to="/console">进入控制台</RouterLink>
      <ThemeToggle />
    </nav>

    <n-button
      class="mobile mobile-menu-button"
      quaternary
      :aria-label="mobileMenuLabel"
      :aria-expanded="mobileMenuOpen"
      aria-controls="public-navigation"
      data-testid="public-menu-toggle"
      @click="mobileMenuOpen = true"
    >
      <template #icon><Menu :size="20" aria-hidden="true" /></template>
      菜单
    </n-button>

    <n-drawer v-model:show="mobileMenuOpen" placement="right" :width="288">
      <n-drawer-content title="导航" closable>
        <nav id="public-navigation" class="mobile-navigation" aria-label="公开页面导航">
          <a href="/#product" @click="closeMobileMenu">产品介绍</a>
          <a href="/#advantages" @click="closeMobileMenu">产品优势</a>
          <RouterLink to="/models" @click="closeMobileMenu">模型目录</RouterLink>
          <RouterLink to="/docs" @click="closeMobileMenu">文档</RouterLink>
          <a href="/#faq" @click="closeMobileMenu">FAQ</a>
          <RouterLink class="console-entry" to="/console" @click="closeMobileMenu">进入控制台</RouterLink>
          <div class="mobile-theme"><span>界面主题</span><ThemeToggle /></div>
        </nav>
      </n-drawer-content>
    </n-drawer>
  </header>
</template>

<style scoped>
.public-header { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 clamp(20px, 4vw, 48px); border-bottom: 1px solid var(--border); background: var(--bg); }
.brand { font-size: 22px; font-weight: 760; letter-spacing: -1.2px; }
.brand span { color: #3b82f6; }
.desktop-navigation { display: flex; align-items: center; gap: 22px; color: var(--text-muted); font-size: 14px; }
.desktop-navigation a:hover { color: var(--text); }
.console-entry { color: #2563eb; font-weight: 650; }
.mobile-menu-button { display: none; }
.mobile-navigation { display: grid; gap: 8px; }
.mobile-navigation a { padding: 10px 8px; border-radius: 6px; color: var(--text); font-weight: 600; }
.mobile-navigation a:hover { background: var(--surface-elevated); }
.mobile-theme { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; padding: 14px 8px 0; border-top: 1px solid var(--border); color: var(--text-muted); }
@media (max-width: 900px) { .desktop-navigation { display: none; } .mobile-menu-button { display: inline-flex; } }
</style>
