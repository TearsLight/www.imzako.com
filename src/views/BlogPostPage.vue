<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { BlogNotFoundError, fetchBlogArticle, type BlogArticle } from '../services/blog'
import { subscribeBlogUpdates } from '../services/blogUpdates'
import { renderMarkdown } from '../services/markdown'
import { siteName } from '../config/sites'

const route = useRoute()
const slug = computed(() =>
  Array.isArray(route.params.slug) ? route.params.slug.join('/') : String(route.params.slug || ''),
)
const article = ref<BlogArticle | null>(null)
const loading = ref(true)
const notice = ref('')
const missing = ref(false)
const content = computed(() =>
  article.value
    ? renderMarkdown(
        article.value.markdown.replace(/^#\s+[^\r\n]+\r?\n/, ''),
        slug.value,
        article.value.updatedAt,
      )
    : '',
)
let controller: AbortController | undefined
let stopUpdates: (() => void) | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let generation = 0

async function load(reset = false) {
  const current = ++generation
  controller?.abort()
  clearTimeout(timer)
  const request = new AbortController()
  controller = request
  if (reset) {
    article.value = null
    missing.value = false
    notice.value = ''
  }
  loading.value = !article.value
  timer = setTimeout(() => request.abort(), 8000)
  try {
    const result = await fetchBlogArticle(slug.value, request.signal)
    if (disposed || current !== generation) return
    article.value = result
    notice.value = ''
    missing.value = false
    document.title = `${result.post.title} — ${siteName}`
  } catch (error) {
    if (disposed || current !== generation) return
    missing.value = error instanceof BlogNotFoundError
    if (missing.value) article.value = null
    notice.value = missing.value ? '这篇文章已被删除或移动。' : '暂时无法同步文章，请稍后重试。'
  } finally {
    if (current === generation) {
      clearTimeout(timer)
      loading.value = false
    }
  }
}

watch(slug, () => {
  void load(true)
})
onMounted(() => {
  void load(true)
  stopUpdates = subscribeBlogUpdates(() => {
    void load()
  })
})
onBeforeUnmount(() => {
  disposed = true
  controller?.abort()
  clearTimeout(timer)
  stopUpdates?.()
})
</script>

<template>
  <div class="article-page">
    <RouterLink class="article-back" to="/blog">← 返回博客</RouterLink>
    <p v-if="loading" class="article-message" role="status">正在展开这篇文章…</p>
    <div v-if="notice" class="article-notice" role="status">
      <p>{{ notice }}</p>
      <button v-if="!missing" type="button" class="button button-outline" @click="load()">
        重新同步
      </button>
    </div>
    <article v-if="article" class="article-document">
      <header class="article-heading">
        <p class="eyebrow">{{ article.post.category }} / THE JOURNAL</p>
        <h1>{{ article.post.title }}</h1>
        <p class="article-date">
          更新于
          <time :datetime="article.updatedAt">{{
            new Date(article.updatedAt).toLocaleString('zh-CN')
          }}</time>
        </p>
      </header>
      <!-- Only HTML returned by the sanitized Markdown renderer is inserted here. -->
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div class="markdown-body" v-html="content" />
      <footer class="article-end">The End <span aria-hidden="true">✳</span></footer>
    </article>
  </div>
</template>

<style scoped>
.article-page {
  max-width: 820px;
  margin-inline: auto;
  padding-block: 40px 64px;
}
.article-back {
  font-size: 14px;
  color: var(--muted);
}
.article-heading {
  padding-block: 42px 28px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 34px;
}
.article-heading .eyebrow {
  color: var(--accent);
}
.article-heading h1 {
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.4;
  margin-top: 18px;
  overflow-wrap: anywhere;
}
.article-date {
  color: var(--muted);
  font-size: 13px;
  margin-top: 20px;
}
.article-message {
  padding-block: 100px;
  text-align: center;
  color: var(--muted);
}
.article-notice {
  padding: 22px;
  margin-top: 24px;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--muted);
}
.article-notice .button {
  margin-top: 15px;
}
.markdown-body {
  font-size: 17px;
  line-height: 1.95;
  overflow-wrap: anywhere;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  line-height: 1.5;
  margin: 1.8em 0 0.8em;
}
.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin-block: 1em;
}
.markdown-body :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.markdown-body :deep(blockquote) {
  margin: 24px 0;
  padding: 10px 22px;
  border-left: 3px solid var(--accent);
  color: var(--muted);
  background: var(--surface);
}
.markdown-body :deep(pre) {
  padding: 22px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  overflow-x: auto;
  line-height: 1.7;
}
.markdown-body :deep(code) {
  font-family: Consolas, monospace;
  font-size: 0.88em;
  background: var(--surface-hover);
  padding: 3px 5px;
  border-radius: 4px;
}
.markdown-body :deep(pre code) {
  padding: 0;
  background: none;
}
.markdown-body :deep(pre.hljs) {
  position: relative;
}
.markdown-body :deep(.hljs-comment),
.markdown-body :deep(.hljs-quote) {
  color: var(--muted);
  font-style: italic;
}
.markdown-body :deep(.hljs-keyword),
.markdown-body :deep(.hljs-selector-tag),
.markdown-body :deep(.hljs-built_in),
.markdown-body :deep(.hljs-name),
.markdown-body :deep(.hljs-tag) {
  color: var(--accent);
}
.markdown-body :deep(.hljs-string),
.markdown-body :deep(.hljs-title),
.markdown-body :deep(.hljs-section),
.markdown-body :deep(.hljs-attribute),
.markdown-body :deep(.hljs-literal),
.markdown-body :deep(.hljs-template-tag),
.markdown-body :deep(.hljs-template-variable),
.markdown-body :deep(.hljs-type) {
  color: color-mix(in srgb, var(--accent) 72%, var(--ink));
}
.markdown-body :deep(.hljs-number),
.markdown-body :deep(.hljs-symbol),
.markdown-body :deep(.hljs-bullet),
.markdown-body :deep(.hljs-variable),
.markdown-body :deep(.hljs-regexp),
.markdown-body :deep(.hljs-link) {
  color: color-mix(in srgb, var(--ink) 70%, var(--muted));
}
.markdown-body :deep(.hljs-meta),
.markdown-body :deep(.hljs-operator),
.markdown-body :deep(.hljs-punctuation) {
  color: var(--muted);
}
.markdown-body :deep(.hljs-emphasis) {
  font-style: italic;
}
.markdown-body :deep(.hljs-strong) {
  font-weight: 700;
}
.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}
.markdown-body :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin-block: 24px;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 10px 18px;
  border: 1px solid var(--line);
  text-align: left;
}
.markdown-body :deep(th) {
  background: var(--surface-hover);
}
.markdown-body :deep(hr) {
  border: 0;
  border-top: 1px solid var(--line);
  margin-block: 36px;
}
.article-end {
  display: flex;
  justify-content: space-between;
  border-top: 1px solid var(--line);
  margin-top: 55px;
  padding-top: 24px;
  color: var(--muted);
  font-size: 13px;
}
.article-end span {
  color: var(--accent);
}
@media (max-width: 700px) {
  .article-page {
    padding-block: 28px 42px;
  }
  .markdown-body {
    font-size: 16px;
  }
}
</style>
