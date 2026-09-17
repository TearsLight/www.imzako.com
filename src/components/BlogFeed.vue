<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  BLOG_PREVIEW_LIMIT,
  fetchBlogPosts,
  prefetchBlogArticle,
  type BlogPost,
} from '../services/blog'
import { subscribeBlogUpdates } from '../services/blogUpdates'
import { prefetchBlogPostView } from '../router/blogView'

const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const section = ref<HTMLElement | null>(null)
const visible = ref(false)
const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const posts = ref<BlogPost[]>([])
const syncError = ref(false)
const visiblePosts = computed(() => posts.value.slice(0, BLOG_PREVIEW_LIMIT))
const sectionId = computed(() => (props.embedded ? 'home-blog' : 'blog-feed'))
let observer: IntersectionObserver | undefined
let controller: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let stopUpdates: (() => void) | undefined
let inFlight = false
let reloadPending = false
let viewPreloadTimer: ReturnType<typeof setTimeout> | undefined

function warmArticle(post: BlogPost) {
  if (!localPath(post.url)) return
  void Promise.allSettled([prefetchBlogPostView(), prefetchBlogArticle(post.id)])
}

function scheduleViewPreload() {
  if (viewPreloadTimer || !posts.value.length) return
  viewPreloadTimer = setTimeout(() => {
    viewPreloadTimer = undefined
    void prefetchBlogPostView().catch(() => {})
  }, 180)
}

async function load() {
  if (inFlight) {
    reloadPending = true
    return
  }
  inFlight = true
  const background = state.value === 'ready'
  if (state.value !== 'ready') state.value = 'loading'
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 8000)
  try {
    const result = await fetchBlogPosts(controller.signal)
    if (!disposed) {
      posts.value = result
      state.value = 'ready'
      syncError.value = false
      scheduleViewPreload()
    }
  } catch {
    if (!disposed) {
      if (background) syncError.value = true
      else state.value = 'error'
    }
  } finally {
    clearTimeout(timer)
    inFlight = false
    if (reloadPending && !disposed) {
      reloadPending = false
      void load()
    }
  }
}

function enter() {
  visible.value = true
  observer?.disconnect()
  stopUpdates ??= subscribeBlogUpdates(() => {
    void load()
  })
  if (state.value === 'idle') void load()
}

function dateLabel(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(value))
}

function localPath(value: string) {
  const url = new URL(value, window.location.origin)
  return url.origin === window.location.origin
    ? `${url.pathname}${url.search}${url.hash}`
    : undefined
}

onMounted(() => {
  if (!props.embedded || typeof IntersectionObserver === 'undefined') {
    enter()
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) enter()
    },
    { rootMargin: '160px 0px', threshold: 0 },
  )
  if (section.value) observer.observe(section.value)
})

onBeforeUnmount(() => {
  disposed = true
  observer?.disconnect()
  clearTimeout(timer)
  clearTimeout(viewPreloadTimer)
  controller?.abort()
  stopUpdates?.()
})
</script>

<template>
  <section
    :id="sectionId"
    ref="section"
    class="blog-feed"
    :class="{ 'is-visible': visible, 'is-embedded': embedded }"
    :aria-labelledby="`${sectionId}-heading`"
  >
    <header class="feed-heading">
      <div>
        <p class="eyebrow">THE JOURNAL <span aria-hidden="true">/</span> 随笔与记录</p>
        <component :is="embedded ? 'h2' : 'h1'" :id="`${sectionId}-heading`" class="feed-title"
          >博客<span class="brand-dot">.</span></component
        >
        <p class="feed-intro">一些探索，一点日常。让想法在这里继续。</p>
      </div>
      <div class="feed-heading-aside">
        <span class="feed-caption">最新文章</span
        ><span v-if="state === 'ready'" class="feed-count">{{
          String(visiblePosts.length).padStart(2, '0')
        }}</span>
      </div>
    </header>

    <div class="feed-content" :aria-busy="state === 'loading'">
      <p v-if="syncError" class="feed-sync-notice" role="status">
        最新内容暂时无法同步。<button type="button" @click="load">重新同步 ↻</button>
      </p>
      <template v-if="state === 'idle' || state === 'loading'">
        <p class="sr-only" role="status">正在加载文章…</p>
        <div class="feed-skeleton" aria-hidden="true">
          <div v-for="n in 3" :key="n"><span /><span /><span /></div>
        </div>
      </template>
      <div v-else-if="state === 'error'" class="feed-empty">
        <p class="eyebrow">PAUSE FOR A MOMENT</p>
        <h3>文章暂时没有加载出来。</h3>
        <p role="alert">连接可能有些慢，请稍后重试。</p>
        <button type="button" class="button button-outline" @click="load">
          重新加载 <span aria-hidden="true">↻</span>
        </button>
      </div>
      <ol v-else-if="visiblePosts.length" class="post-list">
        <li
          v-for="(post, index) in visiblePosts"
          :key="post.id"
          class="post-item"
          :style="{ '--post-delay': `${Math.min(index, 4) * 45}ms` }"
        >
          <article>
            <component
              :is="localPath(post.url) ? RouterLink : 'a'"
              :to="localPath(post.url)"
              :href="post.url"
              class="post-link"
              @pointerenter="warmArticle(post)"
              @pointerdown="warmArticle(post)"
              @focus="warmArticle(post)"
            >
              <span class="post-index" aria-hidden="true">{{
                String(index + 1).padStart(2, '0')
              }}</span>
              <div class="post-copy">
                <div class="post-meta">
                  <span v-if="post.category">{{ post.category }}</span
                  ><time v-if="post.publishedAt" :datetime="post.publishedAt">{{
                    dateLabel(post.publishedAt)
                  }}</time>
                </div>
                <h3>{{ post.title }}</h3>
                <p v-if="post.excerpt">{{ post.excerpt }}</p>
              </div>
              <span class="post-arrow" aria-hidden="true">↗</span>
            </component>
          </article>
        </li>
      </ol>
      <div v-else class="feed-empty">
        <span class="feed-empty-symbol" aria-hidden="true">✳</span>
        <p class="eyebrow">A BLANK PAGE, A NEW BEGINNING</p>
        <h3>故事，从这里开始。</h3>
        <p>这里还没有文章。<br />灵感正在酝酿，期待下一次相遇。</p>
      </div>
    </div>

    <footer class="feed-footer">
      <span>{{
        visiblePosts.length ? '读到这里，谢谢你的停留。' : '留一点空间，给下一次灵感。'
      }}</span>
      <RouterLink v-if="!embedded" class="feed-back" to="/"
        >返回首页 <span aria-hidden="true">↑</span></RouterLink
      >
      <RouterLink v-else class="feed-back" to="/blog"
        >进入博客 <span aria-hidden="true">↗</span></RouterLink
      >
    </footer>
  </section>
</template>

<style scoped>
.blog-feed {
  padding-block: 60px 40px;
  scroll-margin-top: 28px;
}
.blog-feed.is-embedded {
  border-top: 1px solid var(--line);
  margin-top: 20px;
}
.feed-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 28px;
  padding-bottom: 34px;
  border-bottom: 1px solid var(--line);
  opacity: 0;
  transform: translateY(18px);
  transition:
    opacity 650ms var(--ease),
    transform 650ms var(--ease);
}
.is-visible .feed-heading {
  opacity: 1;
  transform: none;
}
.eyebrow {
  color: var(--muted);
}
.eyebrow > span {
  margin-inline: 12px;
  color: var(--accent);
}
.feed-title {
  margin-top: 16px;
  font-size: clamp(40px, 5vw, 64px);
  font-weight: 600;
  letter-spacing: -2px;
  line-height: 1.25;
}
.feed-intro {
  color: var(--muted);
  margin-top: 16px;
  font-size: 16px;
}
.feed-heading-aside {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.feed-caption {
  color: var(--muted);
  font-size: 13px;
}
.feed-count {
  font:
    14px Consolas,
    monospace;
  padding: 6px 9px;
  background: var(--surface-hover);
  border-radius: 5px;
}
.feed-content {
  min-height: 340px;
}
.feed-sync-notice {
  padding-block: 16px;
  color: var(--muted);
  font-size: 13px;
}
.feed-sync-notice button {
  padding: 4px 8px;
  border: 0;
  color: var(--ink);
  background: transparent;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 4px;
}
.post-list {
  padding: 0;
  margin: 0;
  list-style: none;
}
.post-item {
  border-bottom: 1px solid var(--line);
  animation: post-enter 550ms var(--ease) both;
  animation-delay: var(--post-delay);
}
.post-link {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 44px;
  gap: 24px;
  align-items: start;
  padding: 30px 12px;
  border-radius: 6px;
  transition: background-color 300ms;
}
.post-index {
  padding-top: 4px;
  color: var(--muted);
  font:
    12px Consolas,
    monospace;
}
.post-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  color: var(--muted);
  font-size: 12px;
}
.post-meta > span {
  color: var(--accent);
}
.post-copy h3 {
  margin: 10px 0 0;
  font-size: clamp(20px, 2.2vw, 27px);
  font-weight: 550;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.post-copy p {
  max-width: 780px;
  margin-top: 10px;
  color: var(--muted);
  font-size: 16px;
  line-height: 1.85;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.post-arrow {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1px solid var(--line);
  border-radius: 50%;
  font-size: 22px;
  transition:
    transform 350ms var(--ease),
    background-color 250ms,
    color 250ms;
}
.feed-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 340px;
  padding: 45px 16px;
  text-align: center;
}
.feed-empty-symbol {
  font-size: 48px;
  color: var(--accent);
  margin-bottom: 15px;
}
.feed-empty h3 {
  font-size: 25px;
  font-weight: 500;
  margin: 16px 0 12px;
}
.feed-empty > p:not(.eyebrow) {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.9;
}
.feed-empty .button {
  margin-top: 24px;
}
.feed-footer {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding-top: 25px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 13px;
}
.feed-back {
  color: var(--ink);
  flex-shrink: 0;
}
.feed-back span {
  margin-left: 12px;
  color: var(--accent);
}
.feed-skeleton {
  padding: 0 12px;
}
.feed-skeleton > div {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-block: 27px;
  border-bottom: 1px solid var(--line);
}
.feed-skeleton span {
  height: 12px;
  width: 70%;
  background: var(--surface-hover);
  border-radius: 4px;
  animation: loading 1.8s ease-in-out infinite;
}
.feed-skeleton span:first-child {
  width: 110px;
  height: 8px;
}
.feed-skeleton span:nth-child(2) {
  width: 45%;
  height: 22px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@keyframes post-enter {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@keyframes loading {
  50% {
    opacity: 0.4;
  }
}
@media (hover: hover) {
  .post-link:hover {
    background: var(--surface);
  }
  .post-link:hover .post-arrow {
    transform: translate(2px, -2px);
    background: var(--ink);
    color: var(--paper);
  }
}
@media (max-width: 700px) {
  .blog-feed {
    padding-top: 38px;
  }
  .feed-heading {
    gap: 16px;
    padding-bottom: 25px;
  }
  .feed-heading .eyebrow {
    font-size: 10px;
    letter-spacing: 1px;
  }
  .feed-intro {
    font-size: 14px;
    max-width: 220px;
  }
  .feed-heading-aside {
    flex-direction: column;
    align-items: flex-end;
    gap: 6px;
  }
  .post-link {
    grid-template-columns: minmax(0, 1fr) 30px;
    gap: 12px;
    padding: 25px 0;
  }
  .post-index {
    display: none;
  }
  .post-arrow {
    width: 30px;
    height: 30px;
    font-size: 18px;
  }
  .post-copy p {
    font-size: 14px;
  }
  .feed-footer {
    font-size: 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation: none !important;
    transition: none !important;
  }
  .feed-heading {
    opacity: 1;
    transform: none;
  }
}
</style>
