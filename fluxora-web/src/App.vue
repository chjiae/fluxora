<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'
import {
  darkTheme,
  dateZhCN,
  lightTheme,
  zhCN,
} from 'naive-ui'
import { computed } from 'vue'

const themeStore = useThemeStore()
const naiveTheme = computed(() => themeStore.theme === 'dark' ? darkTheme : lightTheme)
</script>

<template>
  <n-config-provider
    :theme="naiveTheme"
    :theme-overrides="themeStore.themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <n-loading-bar-provider>
      <n-notification-provider>
        <n-message-provider>
          <n-dialog-provider>
            <RouterView />
          </n-dialog-provider>
        </n-message-provider>
      </n-notification-provider>
    </n-loading-bar-provider>
  </n-config-provider>
</template>
