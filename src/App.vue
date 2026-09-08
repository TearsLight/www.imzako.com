<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { ref } from 'vue'
import HitokotoFooter from './components/HitokotoFooter.vue'
import { siteLinks } from './config/sites'

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
        <RouterLink class="wordmark" to="/" aria-label="imzako 首页">
          imzako<span class="brand-dot">.</span>
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
          :aria-current="$route.name === 'blog' ? 'page' : undefined"
          >博客</a
        >
      </nav>
      <RouterLink to="/login" class="header-login"
        >用户登录 <span aria-hidden="true">↗</span></RouterLink
      >
    </header>
    <main id="main-content" ref="main" tabindex="-1">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in" @after-enter="focusPage">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
    <footer class="site-footer">
      <span>© {{ year }} imzako</span>
      <HitokotoFooter />
      <span class="footer-domain">www.imzako.com</span>
    </footer>
  </div>
</template>
