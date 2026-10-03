<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useAuth } from '../composables/useAuth'
import {
  createBlogPost,
  deleteBlogPost,
  fetchManagedBlogPost,
  fetchManagedBlogPosts,
  updateBlogPost,
  type ManagedBlogArticle,
} from '../services/blog'

const { currentUser } = useAuth()
const owner = computed(() => currentUser.value?.roles?.includes('OWNER') === true)
const posts = ref<ManagedBlogArticle[]>([])
const selected = ref('')
const busy = ref(false)
const notice = ref('')
const error = ref('')
const creating = ref(false)
const form = reactive({ title: '', tags: '', directory: '', draft: true, markdown: '' })

function clearMessages() {
  notice.value = ''
  error.value = ''
}

function apply(post: ManagedBlogArticle) {
  selected.value = post.slug
  form.title = post.title
  form.tags = post.tags.join(' ')
  form.directory = ''
  form.draft = post.draft
  form.markdown = post.markdown
  creating.value = false
}

async function loadList() {
  if (!owner.value || busy.value) return
  busy.value = true
  clearMessages()
  try {
    posts.value = await fetchManagedBlogPosts()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取博客列表。'
  } finally {
    busy.value = false
  }
}

async function selectPost() {
  if (!selected.value) return
  busy.value = true
  clearMessages()
  try {
    apply(await fetchManagedBlogPost(selected.value))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取博客。'
  } finally {
    busy.value = false
  }
}

function startNew() {
  selected.value = ''
  creating.value = true
  form.title = ''
  form.tags = ''
  form.directory = ''
  form.draft = true
  form.markdown = ''
  clearMessages()
}

async function save() {
  clearMessages()
  if (!form.title.trim()) {
    error.value = '请输入博客标题。'
    return
  }
  busy.value = true
  try {
    if (creating.value) {
      const created = await createBlogPost({
        title: form.title,
        tags: form.tags,
        directory: form.directory,
        draft: form.draft,
      })
      apply(await fetchManagedBlogPost(created.slug))
      notice.value = '博客文件已创建。'
    } else {
      apply(
        await updateBlogPost({
          slug: selected.value,
          title: form.title,
          tags: form.tags,
          draft: form.draft,
          markdown: form.markdown,
        }),
      )
      notice.value = '博客已保存。'
    }
    posts.value = await fetchManagedBlogPosts()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败。'
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!selected.value || !window.confirm(`确定删除“${form.title}”吗？此操作无法撤销。`)) return
  busy.value = true
  clearMessages()
  try {
    await deleteBlogPost(selected.value)
    posts.value = await fetchManagedBlogPosts()
    startNew()
    notice.value = '博客已删除。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除失败。'
  } finally {
    busy.value = false
  }
}

watch(owner, (allowed) => allowed && void loadList(), { immediate: true })
</script>

<template>
  <section v-if="owner" class="blog-admin" aria-labelledby="blog-admin-heading">
    <header>
      <div>
        <p class="eyebrow">OWNER CONTROL</p>
        <h2 id="blog-admin-heading">博客管理</h2>
      </div>
      <button type="button" class="button button-outline" :disabled="busy" @click="startNew">
        新增博客
      </button>
    </header>

    <div class="blog-admin-picker">
      <label for="managed-post">选择博客</label>
      <select id="managed-post" v-model="selected" :disabled="busy" @change="selectPost">
        <option value="">{{ creating ? '正在新建' : '请选择' }}</option>
        <option v-for="post in posts" :key="post.slug" :value="post.slug">
          {{ post.draft ? '[草稿] ' : '' }}{{ post.title }}
        </option>
      </select>
    </div>

    <form class="blog-admin-form" @submit.prevent="save">
      <label>标题<input v-model="form.title" maxlength="200" required /></label>
      <label>标签<input v-model="form.tags" maxlength="500" placeholder="技术 Java" /></label>
      <label v-if="creating">子目录<input v-model="form.directory" maxlength="200" /></label>
      <label class="blog-admin-draft"><input v-model="form.draft" type="checkbox" /> 保存为草稿</label>
      <label v-if="!creating" class="blog-admin-body">
        Markdown 正文
        <textarea v-model="form.markdown" maxlength="1000000" rows="18" />
      </label>
      <div class="blog-admin-actions">
        <button class="button button-dark" type="submit" :disabled="busy">
          {{ busy ? '处理中…' : creating ? '创建' : '保存修改' }}
        </button>
        <button
          v-if="!creating && selected"
          class="button button-outline"
          type="button"
          :disabled="busy"
          @click="remove"
        >
          删除博客
        </button>
      </div>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    </form>
  </section>
</template>

<style scoped>
.blog-admin {
  margin: 42px 0 16px;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
}
.blog-admin > header,
.blog-admin-actions,
.blog-admin-picker {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.blog-admin h2 {
  margin-top: 6px;
  font-size: 24px;
}
.blog-admin-picker {
  justify-content: flex-start;
  margin-top: 24px;
}
.blog-admin-picker select {
  min-width: min(420px, 70vw);
}
.blog-admin-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}
.blog-admin-form label:not(.blog-admin-draft) {
  display: grid;
  gap: 8px;
  color: var(--muted);
  font-size: 13px;
}
.blog-admin-form input,
.blog-admin-form textarea,
.blog-admin-picker select {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--ink);
  background: var(--paper);
  font: inherit;
}
.blog-admin-draft,
.blog-admin-actions,
.blog-admin-body,
.blog-admin-form > p {
  grid-column: 1 / -1;
}
.blog-admin-draft {
  display: flex;
  align-items: center;
  gap: 8px;
}
.blog-admin-actions {
  justify-content: flex-start;
}
.blog-admin-form textarea {
  resize: vertical;
  font-family: Consolas, monospace;
  line-height: 1.6;
}
@media (max-width: 700px) {
  .blog-admin {
    padding: 18px;
  }
  .blog-admin > header,
  .blog-admin-picker {
    align-items: stretch;
    flex-direction: column;
  }
  .blog-admin-form {
    grid-template-columns: 1fr;
  }
  .blog-admin-picker select {
    min-width: 0;
  }
}
</style>
