import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'fluxora-theme'

function readSystemPreference(): Theme {
  if (typeof window === 'undefined') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function readStored(): Theme | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'light' || v === 'dark') return v
  } catch {}
  return null
}

function applyTheme(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme)
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(readStored() || readSystemPreference())

  applyTheme(theme.value)

  // 监听系统主题变化（仅当用户未手动选择时）
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  mq.addEventListener('change', (e) => {
    if (!readStored()) {
      theme.value = e.matches ? 'dark' : 'light'
    }
  })

  watch(theme, (v) => {
    applyTheme(v)
    try { localStorage.setItem(STORAGE_KEY, v) } catch {}
  })

  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  function setTheme(t: Theme) {
    theme.value = t
  }

  return { theme, toggle, setTheme }
})
