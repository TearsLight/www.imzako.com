<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="empty-state not-found reveal" aria-labelledby="not-found-heading">
    <p class="eyebrow">404 / PAGE NOT FOUND</p>
    <h1 id="not-found-heading">页面不存在</h1>
    <p>你访问的页面不存在，回到首页继续探索吧。</p>
    <RouterLink class="button button-dark" to="/"
      >返回首页 <span aria-hidden="true">↗</span></RouterLink
    >
  </section>
</template>
