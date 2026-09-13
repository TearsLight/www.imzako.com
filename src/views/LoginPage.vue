<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  AuthError,
  fetchCaptcha,
  login,
  resendVerification,
  verifyRegistration,
  type AuthUser,
} from '../services/auth'
import { useAuth } from '../composables/useAuth'

const { setUser } = useAuth()
const identity = ref('')
const password = ref('')
const showPassword = ref(false)
const submitted = ref(false)
const pending = ref(false)
const error = ref('')
const user = ref<AuthUser | null>(null)
const identityInput = ref<HTMLInputElement | null>(null)
const passwordInput = ref<HTMLInputElement | null>(null)
const successHeading = ref<HTMLElement | null>(null)

/** Captcha state. The image is fetched only once the server asks for it. */
const captchaId = ref('')
const captchaImage = ref('')
const captchaAnswer = ref('')
const captchaLoading = ref(false)
const captchaError = ref('')

/** Email verification state, used when a correct password meets an unverified account. */
const maskedEmail = ref('')
const code = ref('')
const verifying = ref(false)
const verifyError = ref('')
const resent = ref(false)

const identityError = computed(() =>
  submitted.value && !identity.value.trim() ? '请输入邮箱或 UID。' : '',
)
const passwordError = computed(() => (submitted.value && !password.value ? '请输入密码。' : ''))
const captchaVisible = computed(() => captchaLoading.value || !!captchaImage.value || !!captchaError.value)
let controller: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function loadCaptcha() {
  captchaLoading.value = true
  captchaError.value = ''
  captchaAnswer.value = ''
  try {
    const challenge = await fetchCaptcha()
    if (disposed) return
    captchaId.value = challenge.id
    captchaImage.value = challenge.image
  } catch (cause) {
    if (!disposed) {
      captchaId.value = ''
      captchaImage.value = ''
      captchaError.value = cause instanceof AuthError ? cause.message : '无法获取图形验证码。'
    }
  } finally {
    if (!disposed) captchaLoading.value = false
  }
}

async function submit() {
  if (pending.value) return
  submitted.value = true
  error.value = ''
  if (identityError.value || passwordError.value) {
    await nextTick()
    if (identityError.value) identityInput.value?.focus()
    else passwordInput.value?.focus()
    return
  }
  if (captchaImage.value && !captchaAnswer.value.trim()) {
    error.value = '请输入图形验证码。'
    return
  }

  pending.value = true
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 10000)
  try {
    const result = await login(
      {
        identity: identity.value.trim(),
        password: password.value,
        captchaId: captchaId.value || undefined,
        captchaAnswer: captchaAnswer.value.trim() || undefined,
      },
      controller.signal,
    )
    if (disposed || controller.signal.aborted) return

    if (result.verificationRequired) {
      // Correct password, unconfirmed address: ask for the emailed code.
      maskedEmail.value = result.maskedEmail ?? identity.value.trim()
      code.value = ''
      verifyError.value = ''
      resent.value = false
      await nextTick()
      return
    }

    if (result.user) {
      user.value = result.user
      setUser(result.user)
    }
    password.value = ''
    captchaImage.value = ''
    captchaId.value = ''
    captchaAnswer.value = ''
    await nextTick()
    successHeading.value?.focus()
  } catch (cause) {
    if (disposed) return
    const failure = cause instanceof AuthError ? cause : null
    error.value = failure?.message ?? '登录失败，请稍后重试。'
    // A challenge is consumed by every attempt, so fetch a fresh one for the next try.
    if (failure?.code === 'CAPTCHA_REQUIRED') {
      if (!captchaImage.value) await loadCaptcha()
    } else if (failure?.code === 'INVALID_CAPTCHA') {
      await loadCaptcha()
    }
  } finally {
    clearTimeout(timer)
    if (!disposed) pending.value = false
  }
}

async function verify() {
  if (verifying.value) return
  verifying.value = true
  verifyError.value = ''
  resent.value = false
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 10000)
  try {
    const result = await verifyRegistration(identity.value.trim(), code.value.trim(), controller.signal)
    if (disposed || controller.signal.aborted) return
    if (result.user) {
      user.value = result.user
      setUser(result.user)
    }
    password.value = ''
    await nextTick()
    successHeading.value?.focus()
  } catch (cause) {
    if (!disposed)
      verifyError.value = cause instanceof AuthError ? cause.message : '验证失败，请稍后重试。'
  } finally {
    clearTimeout(timer)
    if (!disposed) verifying.value = false
  }
}

async function resend() {
  if (verifying.value) return
  verifying.value = true
  verifyError.value = ''
  resent.value = false
  try {
    await resendVerification(identity.value.trim())
    if (!disposed) resent.value = true
  } catch (cause) {
    if (!disposed)
      verifyError.value = cause instanceof AuthError ? cause.message : '重发失败，请稍后重试。'
  } finally {
    if (!disposed) verifying.value = false
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
      <template v-if="maskedEmail && !user">
        <p class="eyebrow">VERIFY EMAIL</p>
        <h1 id="login-heading">验证邮箱</h1>
        <p class="login-intro">
          该账号的邮箱 <strong>{{ maskedEmail }}</strong> 尚未验证。请输入邮件中的 6 位验证码完成验证。
        </p>
        <form novalidate :aria-busy="verifying" @submit.prevent="verify">
          <div class="form-field">
            <label for="login-code">验证码</label>
            <input
              id="login-code"
              v-model="code"
              name="code"
              type="text"
              inputmode="numeric"
              autocomplete="one-time-code"
              :spellcheck="false"
              placeholder="6 位数字"
              maxlength="6"
              required
              :disabled="verifying"
            />
          </div>
          <p v-if="verifyError" class="login-error" role="alert">{{ verifyError }}</p>
          <p v-if="resent" class="field-success" role="status">验证码已重新发送。</p>
          <button class="button button-dark login-submit" type="submit" :disabled="verifying">
            <span v-if="verifying" class="loading-spinner" aria-hidden="true" />{{
              verifying ? '正在验证…' : '验证并登录'
            }}<span v-if="!verifying" aria-hidden="true">↗</span>
          </button>
        </form>
        <p class="login-return-note">
          没有收到？<button type="button" class="link-button" :disabled="verifying" @click="resend">
            重新发送验证码
          </button>
        </p>
      </template>
      <template v-else-if="!user">
        <nav class="account-switch" aria-label="账户操作">
          <RouterLink to="/login" aria-current="page">登录</RouterLink>
          <RouterLink to="/register">注册</RouterLink>
        </nav>
        <p class="eyebrow">WELCOME BACK</p>
        <h1 id="login-heading">用户登录</h1>
        <p class="login-intro">登录后继续你的账户服务</p>
        <form novalidate :aria-busy="pending" @submit.prevent="submit">
          <div class="form-field">
            <label for="identity">邮箱或 UID</label>
            <input
              id="identity"
              ref="identityInput"
              v-model="identity"
              name="identity"
              type="text"
              autocomplete="username"
              autocapitalize="none"
              :spellcheck="false"
              placeholder="请输入邮箱或 UID"
              maxlength="254"
              required
              :disabled="pending"
              :aria-invalid="!!identityError"
              :aria-describedby="identityError ? 'identity-error' : undefined"
            />
            <p v-if="identityError" id="identity-error" class="field-error">{{ identityError }}</p>
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
          <div v-if="captchaVisible" class="form-field">
            <label for="captcha-answer">图形验证码</label>
            <div class="captcha-field">
              <input
                id="captcha-answer"
                v-model="captchaAnswer"
                name="captchaAnswer"
                type="text"
                inputmode="text"
                autocomplete="off"
                autocapitalize="characters"
                :spellcheck="false"
                placeholder="图中字符"
                maxlength="8"
                :disabled="pending"
              />
              <img
                v-if="captchaImage"
                class="captcha-image"
                :src="captchaImage"
                alt="图形验证码"
                width="150"
                height="52"
              />
            </div>
            <p v-if="captchaError" class="field-error">{{ captchaError }}</p>
            <button
              type="button"
              class="captcha-refresh"
              :disabled="pending || captchaLoading"
              @click="loadCaptcha"
            >
              {{ captchaLoading ? '正在获取…' : '看不清，换一张' }}
            </button>
            <div class="login-notice">连续输错密码后需要填写图形验证码才能继续尝试。</div>
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
