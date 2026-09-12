<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { AuthError, login, type AuthUser } from '../services/auth'

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const submitted = ref(false)
const pending = ref(false)
const error = ref('')
const user = ref<AuthUser | null>(null)
const usernameInput = ref<HTMLInputElement | null>(null)
const passwordInput = ref<HTMLInputElement | null>(null)
const successHeading = ref<HTMLElement | null>(null)
const usernameError = computed(() =>
  submitted.value && !username.value.trim() ? '请输入账号。' : '',
)
const passwordError = computed(() => (submitted.value && !password.value ? '请输入密码。' : ''))
let controller: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function submit() {
  if (pending.value) return
  submitted.value = true
  error.value = ''
  if (usernameError.value || passwordError.value) {
    await nextTick()
    if (usernameError.value) usernameInput.value?.focus()
    else passwordInput.value?.focus()
    return
  }

  pending.value = true
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 10000)
  try {
    const result = await login(
      { username: username.value.trim(), password: password.value },
      controller.signal,
    )
    if (disposed || controller.signal.aborted) return
    user.value = result.user
    password.value = ''
    await nextTick()
    successHeading.value?.focus()
  } catch (cause) {
    if (!disposed)
      error.value = cause instanceof AuthError ? cause.message : '登录失败，请稍后重试。'
  } finally {
    clearTimeout(timer)
    if (!disposed) pending.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  controller?.abort()
  password.value = ''
})
</script>

<template>
  <section class="login-page" aria-labelledby="login-heading">
    <div class="login-aside reveal">
      <RouterLink class="back-link" to="/"
        ><span aria-hidden="true">←</span> 返回站点导航</RouterLink
      >
      <div>
        <p class="eyebrow">YOUR SPACE, CONNECTED.</p>
        <h2>欢迎回来<span class="brand-dot">。</span><br /><span></span></h2>
      </div>
      <span class="login-aside-foot">IMZAKO / ACCOUNT</span>
    </div>
    <div class="login-panel reveal delay-1">
      <template v-if="!user">
        <nav class="account-switch" aria-label="账户操作">
          <RouterLink to="/login" aria-current="page">登录</RouterLink>
          <RouterLink to="/register">注册</RouterLink>
        </nav>
        <p class="eyebrow">WELCOME BACK</p>
        <h1 id="login-heading">用户登录</h1>
        <p class="login-intro">登录后继续你的账户服务</p>
        <form novalidate :aria-busy="pending" @submit.prevent="submit">
          <div class="form-field">
            <label for="username">账号</label>
            <input
              id="username"
              ref="usernameInput"
              v-model="username"
              name="username"
              type="text"
              autocomplete="username"
              autocapitalize="none"
              :spellcheck="false"
              placeholder="请输入用户名或邮箱"
              maxlength="128"
              required
              :disabled="pending"
              :aria-invalid="!!usernameError"
              :aria-describedby="usernameError ? 'username-error' : undefined"
            />
            <p v-if="usernameError" id="username-error" class="field-error">{{ usernameError }}</p>
          </div>
          <div class="form-field">
            <label for="password">密码</label>
            <div class="password-field">
              <input
                id="password"
                ref="passwordInput"
                v-model="password"
                name="password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
                required
                :disabled="pending"
                :aria-invalid="!!passwordError"
                :aria-describedby="passwordError ? 'password-error' : undefined"
              /><button
                class="password-toggle"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                :disabled="pending"
                @click="showPassword = !showPassword"
              >
                {{ showPassword ? '隐藏' : '显示' }}
              </button>
            </div>
            <p v-if="passwordError" id="password-error" class="field-error">{{ passwordError }}</p>
          </div>
          <p v-if="error" class="login-error" role="alert">{{ error }}</p>
          <button class="button button-dark login-submit" type="submit" :disabled="pending">
            <span v-if="pending" class="loading-spinner" aria-hidden="true" />{{
              pending ? '正在登录…' : '登录'
            }}<span v-if="!pending" aria-hidden="true">↗</span>
          </button>
        </form>
        <p class="login-return-note">
          还没有账户？<RouterLink to="/register"
            >立即注册 <span aria-hidden="true">→</span></RouterLink
          >
        </p>
      </template>
      <div v-else class="login-success">
        <span class="success-mark" aria-hidden="true">✓</span>
        <p class="eyebrow">WELCOME BACK</p>
        <h1 id="login-heading" ref="successHeading" tabindex="-1">登录成功</h1>
        <p>欢迎回来，{{ user.displayName || user.username }}。</p>
        <p v-if="user.roles?.includes('ADMIN')" class="account-role">管理员账户</p>
        <RouterLink class="button button-dark" to="/"
          >返回站点导航 <span aria-hidden="true">↗</span></RouterLink
        >
      </div>
    </div>
  </section>
</template>
