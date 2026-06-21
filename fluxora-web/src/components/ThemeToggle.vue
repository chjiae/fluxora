<script setup lang="ts">
import { Moon, Sun } from 'lucide-vue-next'
import { NButton, NTooltip } from 'naive-ui'
import { computed } from 'vue'

import { useThemeStore } from '@/stores/theme'

const themeStore = useThemeStore()
const nextThemeLabel = computed(() => themeStore.theme === 'dark' ? '亮色' : '深色')
const ariaLabel = computed(() => `切换为${nextThemeLabel.value}主题`)
</script>

<template>
  <n-tooltip>
    <template #trigger>
      <n-button
        quaternary
        circle
        :aria-label="ariaLabel"
        @click="themeStore.toggle"
      >
        <template #icon>
          <Sun v-if="themeStore.theme === 'dark'" :size="18" aria-hidden="true" />
          <Moon v-else :size="18" aria-hidden="true" />
        </template>
      </n-button>
    </template>
    {{ ariaLabel }}
  </n-tooltip>
</template>
