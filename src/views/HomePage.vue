<script setup lang="ts">
import { sites } from '../config/sites'
import BlogFeed from '../components/BlogFeed.vue'

function scrollToBlog() {
  document.getElementById('home-blog')?.scrollIntoView({
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    block: 'start',
  })
}
</script>

<template>
  <div class="home-page">
    <section class="portal-intro reveal" aria-labelledby="home-heading">
      <div>
        <p class="eyebrow">
          <span class="small-cross" aria-hidden="true">✳</span>
          PERSONAL PORTAL
        </p>
        <h1 id="home-heading">Connect To</h1>
        <p class="portal-description">The Truth</p>
      </div>
      <div class="portal-signature" aria-hidden="true">
        <span>Hello World!<span class="brand-dot">.</span></span
        ><small>A PLACE FOR EVERYTHING</small>
      </div>
    </section>

    <section class="services-section" aria-labelledby="services-heading">
      <div class="section-heading reveal delay-1">
        <h2 id="services-heading">站点导航 <span>DIRECTORY</span></h2>
      </div>
      <div class="service-grid">
        <component
          :is="site.href ? 'a' : 'article'"
          v-for="(site, index) in sites"
          :key="site.id"
          :href="site.href"
          :target="site.id === 'git' ? '_blank' : undefined"
          :rel="site.id === 'git' ? 'noopener noreferrer' : undefined"
          class="service-card reveal"
          :class="[
            { 'service-card-placeholder': !site.href },
            `delay-${Math.min(index + 1, 3)}`,
          ]"
          :aria-label="
            site.href
              ? `${site.name} — ${site.address}${site.id === 'git' ? '（新窗口打开）' : ''}`
              : undefined
          "
        >
          <div class="service-card-top">
            <span class="service-icon" aria-hidden="true">
              <svg
                v-if="site.icon === 'git'"
                viewBox="0 0 32 32"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
              >
                <circle cx="10" cy="7" r="3" />
                <circle cx="10" cy="25" r="3" />
                <circle cx="24" cy="8" r="3" />
                <path d="M10 10v12m14-11c0 7-14 3-14 11" />
              </svg>
              <svg
                v-else-if="site.icon === 'blog'"
                viewBox="0 0 32 32"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
              >
                <rect x="6" y="4" width="20" height="24" rx="3" />
                <path d="M12 11h8m-8 5h8m-8 5h5" />
              </svg>
              <svg v-else viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M16 7v18M7 16h18" />
              </svg>
            </span>
            <span class="service-label">{{ site.label }}</span>
            <span v-if="site.href" class="service-arrow" aria-hidden="true">↗</span>
            <span v-else class="reserved-badge">Comming soon</span>
          </div>
          <div class="service-copy">
            <h3>{{ site.name }}</h3>
            <p>{{ site.description }}</p>
          </div>
          <div v-if="site.href" class="service-card-bottom">
            <span>{{ site.address }}</span
            ><span>进入站点 <span aria-hidden="true">→</span></span>
          </div>
        </component>
      </div>
    </section>
    <div class="portal-note reveal delay-3">
      <button type="button" class="blog-scroll-cue" aria-controls="home-blog" @click="scrollToBlog">
        <span>向下探索博客</span><span aria-hidden="true">↓</span>
      </button>
      <span class="portal-note-end">SCROLL TO THE JOURNAL</span>
    </div>
    <BlogFeed embedded />
  </div>
</template>

<style scoped>
.blog-scroll-cue {
  display: inline-flex;
  align-items: center;
  gap: 16px;
  min-height: 44px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font-size: 13px;
}
.blog-scroll-cue > span:last-child {
  color: var(--accent);
  transition: transform 300ms var(--ease);
}
.blog-scroll-cue:hover > span:last-child {
  transform: translateY(4px);
}
@media (prefers-reduced-motion: reduce) {
  .blog-scroll-cue > span:last-child {
    transition: none;
  }
}
</style>
