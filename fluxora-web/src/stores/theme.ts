import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type Theme = 'light' | 'dark'
const STORAGE_KEY = 'fluxora-theme'

function readSystemPreference(): Theme {
  if (typeof window === 'undefined') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}
function readStored(): Theme | null {
  try { const v = localStorage.getItem(STORAGE_KEY); if (v === 'light' || v === 'dark') return v } catch { }
  return null
}
function applyTheme(theme: Theme) { document.documentElement.setAttribute('data-theme', theme) }

function buildOverrides(theme: Theme) {
  const isDark = theme === 'dark'
  return {
    common: {
      primaryColor: isDark ? '#2dd4bf' : '#16a394',
      primaryColorHover: isDark ? '#5ee0cd' : '#1db8a6',
      primaryColorPressed: isDark ? '#1abc9c' : '#128578',
      fontFamily: "-apple-system,BlinkMacSystemFont,'SF Pro Text','SF Pro Display','PingFang SC','Hiragino Sans GB','Microsoft YaHei UI',sans-serif",
      fontFamilyMono: "'SF Mono',Consolas,monospace",
      fontSize: '14px', borderRadius: '8px', lineHeight: '1.55',
      bodyColor: isDark ? '#0a0a09' : '#ffffff',
      cardColor: isDark ? '#151514' : '#f7f7f5',
      modalColor: isDark ? '#151514' : '#f7f7f5',
      popoverColor: isDark ? '#151514' : '#f7f7f5',
      textColor1: isDark ? '#e8e6e3' : '#151515',
      textColor2: isDark ? '#989694' : '#565653',
      borderColor: isDark ? '#333231' : '#dddcd7',
      dividerColor: isDark ? '#333231' : '#dddcd7',
      inputColor: isDark ? '#1c1c1b' : '#f5f4f0',
      tableColor: isDark ? '#1c1c1b' : '#ffffff',
      tableHeaderColor: isDark ? '#262625' : '#fafaf8',
    },
    Button: { borderRadiusMedium: '8px' },
    Tag: { borderRadius: '10px' },
    Drawer: { borderRadius: '12px' },
    Dialog: { borderRadius: '12px' },
    DataTable: { borderRadius: '8px', tdPaddingMedium: '10px 12px', thPaddingMedium: '10px 12px' },
  }
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(readStored() || readSystemPreference())
  const themeOverrides = ref(buildOverrides(theme.value))
  applyTheme(theme.value)

  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  mq.addEventListener('change', (e) => { if (!readStored()) { theme.value = e.matches ? 'dark' : 'light' } })

  watch(theme, (v) => {
    applyTheme(v)
    themeOverrides.value = buildOverrides(v)
    try { localStorage.setItem(STORAGE_KEY, v) } catch { }
  })

  function toggle() { theme.value = theme.value === 'dark' ? 'light' : 'dark' }
  function setTheme(t: Theme) { theme.value = t }

  return { theme, themeOverrides, toggle, setTheme }
})
