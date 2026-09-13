<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { onBeforeUnmount, ref, watch } from 'vue'
import HitokotoFooter from './components/HitokotoFooter.vue'
import ThemeToggle from './components/ThemeToggle.vue'
import { siteLinks, siteName } from './config/sites'
import { useAuth } from './composables/useAuth'

const { currentUser, greeting, initial, avatar, isAuthenticated, signOut } = useAuth()
const main = ref<HTMLElement | null>(null)
const accountMenu = ref<HTMLElement | null>(null)
const menuOpen = ref(false)
const year = new Date().getFullYear()

function focusPage() {
  main.value?.focus({ preventScroll: true })
}

function closeMenu() {
  menuOpen.value = false
}

function onDocumentPointerDown(event: PointerEvent) {
  if (!menuOpen.value) return
  if (!accountMenu.value?.contains(event.target as Node)) closeMenu()
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeMenu()
}

watch(menuOpen, (open) => {
  if (open) {
    document.addEventListener('pointerdown', onDocumentPointerDown)
    document.addEventListener('keydown', onDocumentKeydown)
  } else {
    document.removeEventListener('pointerdown', onDocumentPointerDown)
    document.removeEventListener('keydown', onDocumentKeydown)
  }
})

// Signing out from anywhere closes the menu.
watch(isAuthenticated, (signedIn) => {
  if (!signedIn) closeMenu()
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<template>
  <a class="skip-link" href="#main-content">跳转到主要内容</a>
  <div class="site-shell">
    <header class="site-header">
      <div class="header-brand">
        <RouterLink class="wordmark" to="/" :aria-label="`${siteName} 首页`">
          <span class="brand-dot">0.o</span>
        </RouterLink>

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
        <div v-if="isAuthenticated" ref="accountMenu" class="header-account">
          <button
            type="button"
            class="header-greeting"
            :aria-expanded="menuOpen"
            aria-haspopup="menu"
            :aria-label="`${greeting}，打开用户管理菜单`"
            @click="menuOpen = !menuOpen"
          >
            <span class="avatar-badge" aria-hidden="true">
              <img v-if="avatar" :src="avatar" alt="" />
              <span v-else>{{ initial }}</span>
            </span>
            <span class="header-greeting-label">{{ greeting }}</span>
            <span class="header-greeting-caret" aria-hidden="true">▾</span>
          </button>
          <div v-if="menuOpen" class="account-menu" role="menu">
            <div class="account-menu-head">
              <span class="account-menu-name">{{ greeting }}</span>
              <span class="account-menu-uid">UID {{ currentUser?.id }}</span>
            </div>
            <RouterLink class="account-menu-item" role="menuitem" to="/account" @click="closeMenu"
              >用户管理</RouterLink
            >
            <button
              type="button"
              class="account-menu-item account-menu-signout"
              role="menuitem"
              @click="signOut"
            >
              退出登录
            </button>
          </div>
        </div>
        <RouterLink v-else to="/login" class="header-login"
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
      <span>© {{ year }} Cherry Lanterns</span>
      <HitokotoFooter />
    </footer>
  </div>
</template>
