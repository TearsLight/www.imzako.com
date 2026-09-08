<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="blog-page">
    <header class="blog-heading reveal">
      <div>
        <p class="eyebrow">THE JOURNAL</p>
        <h1>博客<span class="brand-dot">.</span></h1>
        <p class="blog-description">思考、探索，与值得记录的日常。</p>
      </div>
      <span class="article-count">文章 <span>00</span></span>
    </header>
    <section class="empty-state reveal delay-1" aria-labelledby="empty-heading">
      <div class="empty-mark" aria-hidden="true">
        <span>[</span><span class="empty-spark">✳</span><span>]</span>
      </div>
      <p class="eyebrow">A BLANK PAGE, A NEW BEGINNING</p>
      <h2 id="empty-heading">故事从这里开始。</h2>
      <p>这里还没有文章。<br />大芬慢慢酿，心急味则散。</p>
      <RouterLink class="button button-outline" to="/"
        ><span aria-hidden="true">←</span> 返回首页</RouterLink
      >
    </section>
  </div>
</template>
