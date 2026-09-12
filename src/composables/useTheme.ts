import { computed, ref } from 'vue'

export type Theme = 'light' | 'dark'

const storageKey = 'imzako:theme'
const theme = ref<Theme>('light')

function storedTheme(): Theme | null {
  try {
    const stored = localStorage.getItem(storageKey)
    return stored === 'light' || stored === 'dark' ? stored : null
  } catch {
    return null
  }
}

function systemTheme(): Theme {
  return typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light'
}

function applyTheme(nextTheme: Theme, persist: boolean) {
  theme.value = nextTheme
  document.documentElement.dataset.theme = nextTheme
  document.documentElement.style.colorScheme = nextTheme
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', nextTheme === 'dark' ? '#080808' : '#f9f8f5')
  if (persist) {
    try {
      localStorage.setItem(storageKey, nextTheme)
    } catch {
      // The selected theme still applies when browser storage is unavailable.
    }
  }
}

export function initializeTheme() {
  applyTheme(storedTheme() ?? systemTheme(), false)
}

export function useTheme() {
  const isDark = computed(() => theme.value === 'dark')
  const toggleTheme = () => applyTheme(isDark.value ? 'light' : 'dark', true)

  return { theme, isDark, toggleTheme }
}
