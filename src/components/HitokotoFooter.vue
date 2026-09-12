<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

type Sentence = { hitokoto: string; uuid: string; from: string }
type CachedSentence = { sentence: Sentence; expiresAt: number }

const cacheKey = 'imzako:hitokoto:v1'
const cacheDuration = 60 * 60 * 1000
const apiUrl =
  import.meta.env.VITE_HITOKOTO_API_URL || 'https://v1.hitokoto.cn/?encode=json&max_length=60'
const sentence = ref<Sentence | null>(null)
const sourceUrl = computed(() =>
  sentence.value
    ? `https://hitokoto.cn?uuid=${encodeURIComponent(sentence.value.uuid)}`
    : undefined,
)
const controller = new AbortController()
let timeout: ReturnType<typeof setTimeout> | undefined

function isSentence(value: unknown): value is Sentence {
  if (!value || typeof value !== 'object') return false
  const data = value as Partial<Sentence>
  return (
    typeof data.hitokoto === 'string' &&
    data.hitokoto.trim().length > 0 &&
    typeof data.uuid === 'string' &&
    /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(data.uuid) &&
    typeof data.from === 'string'
  )
}

onMounted(async () => {
  try {
    const cached: CachedSentence | null = JSON.parse(localStorage.getItem(cacheKey) ?? 'null')
    if (cached && isSentence(cached.sentence)) {
      // Keep a previously loaded sentence visible if refreshing the cache fails.
      sentence.value = cached.sentence
      if (Number.isFinite(cached.expiresAt) && cached.expiresAt > Date.now()) return
    }
  } catch {
    // Storage may be disabled or contain an outdated value; the API still works.
  }

  timeout = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(apiUrl, {
      signal: controller.signal,
    })
    if (!response.ok) return
    const data: unknown = await response.json()
    if (!isSentence(data) || controller.signal.aborted) return
    sentence.value = { hitokoto: data.hitokoto, uuid: data.uuid, from: data.from }
    try {
      localStorage.setItem(
        cacheKey,
        JSON.stringify({ sentence: sentence.value, expiresAt: Date.now() + cacheDuration }),
      )
    } catch {
      // A failed cache write must not discard the successfully loaded sentence.
    }
  } catch {
    // Offline, timeout, and invalid JSON all retain the cached text or fallback.
  } finally {
    clearTimeout(timeout)
  }
})

onBeforeUnmount(() => {
  clearTimeout(timeout)
  controller.abort()
})
</script>

<template>
  <span class="footer-message">
    <a
      v-if="sentence"
      :href="sourceUrl"
      target="_blank"
      rel="noopener noreferrer"
      :title="`一言 · ${sentence.from || '查看句子详情'}（新窗口打开）`"
    >
      {{ sentence.hitokoto }}<span aria-hidden="true"> ↗</span>
    </a>
  </span>
</template>
