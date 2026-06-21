import { defineStore } from 'pinia'
import type { GlobalThemeOverrides } from 'naive-ui'
import { onScopeDispose, ref, watch } from 'vue'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'fluxora-theme'

function readSystemPreference(): Theme {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function readStoredTheme(): Theme | null {
  if (typeof window === 'undefined') return null
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    return null
  }
}

function applyTheme(theme: Theme): void {
  if (typeof document !== 'undefined') document.documentElement.setAttribute('data-theme', theme)
}

/** 冷蓝品牌主题：亮暗模式保持相同语义色，避免状态含义随主题改变。 */
export function buildThemeOverrides(theme: Theme): GlobalThemeOverrides {
  const isDark = theme === 'dark'
  return {
    common: {
      primaryColor: '#4f7cff',
      primaryColorHover: '#6b90ff',
      primaryColorPressed: '#3d67e8',
      primaryColorSuppl: '#dce6ff',
      infoColor: '#4f7cff',
      infoColorHover: '#6b90ff',
      infoColorPressed: '#3d67e8',
      successColor: '#20a779',
      successColorHover: '#36b98b',
      successColorPressed: '#16865f',
      warningColor: '#d98b20',
      warningColorHover: '#e5a143',
      warningColorPressed: '#b96e10',
      errorColor: '#dd5a63',
      errorColorHover: '#e7757c',
      errorColorPressed: '#bd424b',
      fontFamily: "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Microsoft YaHei UI', sans-serif",
      fontFamilyMono: "'SF Mono', Consolas, monospace",
      fontSize: '14px',
      borderRadius: '8px',
      lineHeight: '1.55',
      bodyColor: isDark ? '#11151c' : '#f7f8fa',
      cardColor: isDark ? '#191f29' : '#ffffff',
      modalColor: isDark ? '#191f29' : '#ffffff',
      popoverColor: isDark ? '#191f29' : '#ffffff',
      textColor1: isDark ? '#f2f5fa' : '#182230',
      textColor2: isDark ? '#a9b5c6' : '#617083',
      textColor3: isDark ? '#7d8ba0' : '#8491a3',
      borderColor: isDark ? '#2b3545' : '#e3e7ee',
      dividerColor: isDark ? '#2b3545' : '#e3e7ee',
      inputColor: isDark ? '#191f29' : '#ffffff',
      tableColor: isDark ? '#191f29' : '#ffffff',
      tableHeaderColor: isDark ? '#202837' : '#f0f3f8',
    },
    Button: { borderRadiusMedium: '8px' },
    Tag: { borderRadius: '10px' },
    Drawer: { borderRadius: '12px' },
    Dialog: { borderRadius: '12px' },
    DataTable: { borderRadius: '8px', tdPaddingMedium: '10px 12px', thPaddingMedium: '10px 12px' },
  }
}

export const useThemeStore = defineStore('theme', () => {
  const storedTheme = readStoredTheme()
  // 仅用于当前 store 生命周期内判断是否由用户主动选择，无需进入 Pinia 响应式状态。
  let hasManualPreference = storedTheme !== null
  const theme = ref<Theme>(storedTheme ?? readSystemPreference())
  const themeOverrides = ref<GlobalThemeOverrides>(buildThemeOverrides(theme.value))

  applyTheme(theme.value)

  const mediaQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null
  const handleSystemThemeChange = (event: MediaQueryListEvent) => {
    if (!hasManualPreference) theme.value = event.matches ? 'dark' : 'light'
  }
  mediaQuery?.addEventListener('change', handleSystemThemeChange)
  onScopeDispose(() => mediaQuery?.removeEventListener('change', handleSystemThemeChange))

  watch(theme, (value) => {
    applyTheme(value)
    themeOverrides.value = buildThemeOverrides(value)
  })

  /** 用户主动切换后才写入本地，系统主题变化仍可继续跟随。 */
  function setTheme(value: Theme): void {
    hasManualPreference = true
    theme.value = value
    if (typeof window !== 'undefined') {
      try {
        window.localStorage.setItem(STORAGE_KEY, value)
      } catch {
        // 隐私模式下存储不可用不影响当前主题切换。
      }
    }
  }

  function toggle(): void {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, themeOverrides, toggle, setTheme }
})
