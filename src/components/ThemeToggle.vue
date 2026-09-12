<script setup lang="ts">
import { initializeTheme, useTheme } from '../composables/useTheme'

initializeTheme()
const { isDark, toggleTheme } = useTheme()
</script>

<template>
  <button
    class="theme-toggle"
    type="button"
    role="switch"
    :aria-checked="isDark"
    :aria-label="isDark ? '切换为白天模式' : '切换为夜间模式'"
    :title="isDark ? '当前为夜间模式，点击切换为白天模式' : '当前为白天模式，点击切换为夜间模式'"
    @click="toggleTheme"
  >
    <svg class="theme-icon theme-icon-sun" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="3.5" />
      <path
        d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M18.7 5.3l-1.4 1.4M6.7 17.3l-1.4 1.4"
      />
    </svg>
    <span class="theme-track" aria-hidden="true"><span class="theme-thumb" /></span>
    <svg class="theme-icon theme-icon-moon" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M19.3 15.5A8 8 0 0 1 8.5 4.7a8 8 0 1 0 10.8 10.8Z" />
    </svg>
  </button>
</template>
