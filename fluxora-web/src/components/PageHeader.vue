<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <header class="page-header">
    <div><h1>{{ title }}</h1><p v-if="description">{{ description }}</p></div>
    <div class="page-header-actions"><slot /></div>
  </header>
</template>

<style scoped>
.page-header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;margin-bottom:24px}.page-header h1{margin:0;font-size:1.4rem}.page-header p{margin:6px 0 0;color:var(--text-muted);font-size:14px}.page-header-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}@media(max-width:600px){.page-header{flex-direction:column}}
</style>
