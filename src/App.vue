<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { ref } from 'vue'
import HitokotoFooter from './components/HitokotoFooter.vue'
import ThemeToggle from './components/ThemeToggle.vue'
import { publicDomain, siteLinks, siteName } from './config/sites'

const main = ref<HTMLElement | null>(null)
const year = new Date().getFullYear()
function focusPage() {
  main.value?.focus({ preventScroll: true })
}
</script>

<template>
  <a class="skip-link" href="#main-content">跳转到主要内容</a>
  <div class="site-shell">
    <header class="site-header">
      <div class="header-brand">
        <RouterLink class="wordmark" to="/" :aria-label="`${siteName} 首页`">
          {{ siteName }}<span class="brand-dot">.</span>
        </RouterLink>
        <span class="header-subtitle">个人服务门户</span>
      </div>
      <nav class="navigation" aria-label="主导航">
        <RouterLink to="/" class="nav-link">首页</RouterLink>
        <a
          :href="siteLinks.git"
          class="nav-link"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="Git（新窗口打开）"
          >Git <span aria-hidden="true">↗</span></a
        >
        <a
          :href="siteLinks.blog"
          class="nav-link"
          :aria-current="$route.name === 'blog' || $route.name === 'blog-post' ? 'page' : undefined"
          >博客</a
        >
      </nav>
      <div class="header-actions">
        <ThemeToggle />
        <RouterLink to="/register" class="header-register">注册</RouterLink>
        <RouterLink to="/login" class="header-login"
          >用户登录 <span aria-hidden="true">↗</span></RouterLink
        >
      </div>
    </header>
    <main id="main-content" ref="main" tabindex="-1">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in" @after-enter="focusPage">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
    <footer class="site-footer">
      <span>© {{ year }} {{ siteName }}</span>
      <HitokotoFooter />
      <span class="footer-domain">{{ publicDomain }}</span>
    </footer>
  </div>
</template>
