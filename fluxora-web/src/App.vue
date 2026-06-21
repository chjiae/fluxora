<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'
import { lightTheme, darkTheme } from 'naive-ui'
import { computed } from 'vue'

const themeStore = useThemeStore()
const naiveTheme = computed(() => themeStore.theme === 'dark' ? darkTheme : lightTheme)
</script>

<template>
  <n-config-provider :theme="naiveTheme" :theme-overrides="themeStore.themeOverrides">
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
