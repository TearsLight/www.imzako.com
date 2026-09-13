<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  AuthError,
  register,
  resendVerification,
  verifyRegistration,
  type AuthUser,
} from '../services/auth'
import { useAuth } from '../composables/useAuth'

const { setUser } = useAuth()
const email = ref('')
const username = ref('')
const password = ref('')
const passwordConfirmation = ref('')
const showPassword = ref(false)
const submitted = ref(false)
const pending = ref(false)
const error = ref('')
/** Set once the account exists; the panel then asks for the emailed code. */
const maskedEmail = ref('')
const verifiedUser = ref<AuthUser | null>(null)
const code = ref('')
const verifying = ref(false)
const verifyError = ref('')
const resent = ref(false)

const emailInput = ref<HTMLInputElement | null>(null)
const usernameInput = ref<HTMLInputElement | null>(null)
const passwordInput = ref<HTMLInputElement | null>(null)
const passwordConfirmationInput = ref<HTMLInputElement | null>(null)
const successHeading = ref<HTMLElement | null>(null)
const codeInput = ref<HTMLInputElement | null>(null)

const successName = computed(
  () => verifiedUser.value?.displayName?.trim() || verifiedUser.value?.username || '',
)
const successUid = computed(() => verifiedUser.value?.id ?? '')

const emailError = computed(() => {
  if (!submitted.value) return ''
  const value = email.value.trim()
  if (!value) return '请输入邮箱。'
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) ? '' : '请输入有效的邮箱地址。'
})
const usernameError = computed(() => {
  if (!submitted.value) return ''
  const value = username.value.trim()
  if (!value) return '请输入用户名。'
  if (value.length < 3 || value.length > 32) return '用户名长度应为 3–32 个字符。'
  return /^[\p{L}\p{N}_.-]+$/u.test(value) ? '' : '用户名只能包含文字、数字、点、横线和下划线。'
})
const passwordError = computed(() => {
  if (!submitted.value) return ''
  if (!password.value) return '请输入密码。'
  return password.value.length >= 8 && password.value.length <= 128
    ? ''
    : '密码长度应为 8–128 个字符。'
})
const passwordConfirmationError = computed(() => {
  if (!submitted.value) return ''
  if (!passwordConfirmation.value) return '请再次输入密码。'
  return passwordConfirmation.value === password.value ? '' : '两次输入的密码不一致。'
})

let controller: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function focusFirstError() {
  await nextTick()
  if (emailError.value) emailInput.value?.focus()
  else if (usernameError.value) usernameInput.value?.focus()
  else if (passwordError.value) passwordInput.value?.focus()
  else if (passwordConfirmationError.value) passwordConfirmationInput.value?.focus()
}

async function submit() {
  if (pending.value) return
  submitted.value = true
  error.value = ''
  if (
    emailError.value ||
    usernameError.value ||
    passwordError.value ||
    passwordConfirmationError.value
  ) {
    await focusFirstError()
    return
  }

  pending.value = true
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 10000)
  try {
    const result = await register(
      {
        email: email.value.trim(),
        username: username.value.trim(),
        password: password.value,
      },
      controller.signal,
    )
    if (disposed || controller.signal.aborted) return
    // The account exists but has no session yet: the emailed code comes next.
    maskedEmail.value = result.maskedEmail ?? email.value.trim()
    code.value = ''
    verifyError.value = ''
    resent.value = false
    password.value = ''
    passwordConfirmation.value = ''
    await nextTick()
    codeInput.value?.focus()
  } catch (cause) {
    if (!disposed)
      error.value = cause instanceof AuthError ? cause.message : '注册失败，请稍后重试。'
  } finally {
    clearTimeout(timer)
    if (!disposed) pending.value = false
  }
}

async function verify() {
  if (verifying.value) return
  verifyError.value = ''
  resent.value = false
  if (!code.value.trim()) {
    verifyError.value = '请输入邮件中的验证码。'
    return
  }

  verifying.value = true
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 10000)
  try {
    const result = await verifyRegistration(email.value.trim(), code.value.trim(), controller.signal)
    if (disposed || controller.signal.aborted) return
    if (result.user) {
      verifiedUser.value = result.user
      setUser(result.user)
    }
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
  verifyError.value = ''
  resent.value = false
  verifying.value = true
  controller = new AbortController()
  timer = setTimeout(() => controller?.abort(), 10000)
  try {
    await resendVerification(email.value.trim(), controller.signal)
    if (disposed || controller.signal.aborted) return
    resent.value = true
  } catch (cause) {
    if (!disposed)
      verifyError.value = cause instanceof AuthError ? cause.message : '重发失败，请稍后重试。'
  } finally {
    clearTimeout(timer)
    if (!disposed) verifying.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  controller?.abort()
  password.value = ''
  passwordConfirmation.value = ''
})
</script>

<template>
  <section class="login-page register-page" aria-labelledby="register-heading">
    <div class="login-aside reveal">
      <RouterLink class="back-link" to="/"
        ><span aria-hidden="true">←</span> 返回站点导航</RouterLink
      >
      <div>
        <p class="eyebrow">CREATE YOUR SPACE.</p>
        <h2>从这里开始<span class="brand-dot">。</span></h2>
        <p class="login-aside-description">创建账户后将自动登录。你的密码只会以加盐哈希保存。</p>
      </div>
      <span class="login-aside-foot">IMZAKO / JOIN</span>
    </div>

    <div class="login-panel register-panel reveal delay-1">
      <template v-if="!maskedEmail && !verifiedUser">
        <nav class="account-switch" aria-label="账户操作">
          <RouterLink to="/login">登录</RouterLink>
          <RouterLink to="/register" aria-current="page">注册</RouterLink>
        </nav>
        <p class="eyebrow">CREATE ACCOUNT</p>
        <h1 id="register-heading">创建账户</h1>
        <p class="login-intro">注册后即可使用账户服务</p>

        <form novalidate :aria-busy="pending" @submit.prevent="submit">
          <div class="form-field">
            <label for="register-email">邮箱</label>
            <input
              id="register-email"
              ref="emailInput"
              v-model="email"
              name="email"
              type="email"
              autocomplete="email"
              autocapitalize="none"
              :spellcheck="false"
              placeholder="name@example.com"
              maxlength="254"
              required
              :disabled="pending"
              :aria-invalid="!!emailError"
              :aria-describedby="emailError ? 'register-email-error' : undefined"
            />
            <p v-if="emailError" id="register-email-error" class="field-error">
              {{ emailError }}
            </p>
          </div>

          <div class="form-field">
            <label for="register-username">用户名</label>
            <input
              id="register-username"
              ref="usernameInput"
              v-model="username"
              name="username"
              type="text"
              autocomplete="username"
              autocapitalize="none"
              :spellcheck="false"
              placeholder="3–32 个字符"
              minlength="3"
              maxlength="32"
              required
              :disabled="pending"
              :aria-invalid="!!usernameError"
              :aria-describedby="
                usernameError
                  ? 'register-username-hint register-username-error'
                  : 'register-username-hint'
              "
            />
            <p id="register-username-hint" class="form-hint">可使用文字、数字、点、横线和下划线</p>
            <p v-if="usernameError" id="register-username-error" class="field-error">
              {{ usernameError }}
            </p>
          </div>

          <div class="form-field">
            <label for="register-password">密码</label>
            <div class="password-field">
              <input
                id="register-password"
                ref="passwordInput"
                v-model="password"
                name="password"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="new-password"
                placeholder="至少 8 个字符"
                minlength="8"
                maxlength="128"
                required
                :disabled="pending"
                :aria-invalid="!!passwordError"
                :aria-describedby="passwordError ? 'register-password-error' : undefined"
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
            <p v-if="passwordError" id="register-password-error" class="field-error">
              {{ passwordError }}
            </p>
          </div>

          <div class="form-field">
            <label for="register-password-confirmation">确认密码</label>
            <input
              id="register-password-confirmation"
              ref="passwordConfirmationInput"
              v-model="passwordConfirmation"
              name="passwordConfirmation"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="再次输入密码"
              minlength="8"
              maxlength="128"
              required
              :disabled="pending"
              :aria-invalid="!!passwordConfirmationError"
              :aria-describedby="
                passwordConfirmationError ? 'register-password-confirmation-error' : undefined
              "
            />
            <p
              v-if="passwordConfirmationError"
              id="register-password-confirmation-error"
              class="field-error"
            >
              {{ passwordConfirmationError }}
            </p>
          </div>

          <p v-if="error" class="login-error" role="alert">{{ error }}</p>
          <button class="button button-dark login-submit" type="submit" :disabled="pending">
            <span v-if="pending" class="loading-spinner" aria-hidden="true" />{{
              pending ? '正在创建账户…' : '注册并发送验证码'
            }}<span v-if="!pending" aria-hidden="true">↗</span>
          </button>
        </form>

        <p class="login-return-note">
          已有账户？<RouterLink to="/login">直接登录 <span aria-hidden="true">→</span></RouterLink>
        </p>
      </template>

      <template v-else-if="maskedEmail && !verifiedUser">
        <p class="eyebrow">VERIFY EMAIL</p>
        <h1 id="register-heading">输入验证码</h1>
        <p class="login-intro">
          账号已创建。我们已向 <strong>{{ maskedEmail }}</strong> 发送 6 位验证码，请查收后完成验证。
        </p>

        <form novalidate :aria-busy="verifying" @submit.prevent="verify">
          <div class="form-field">
            <label for="register-code">验证码</label>
            <input
              id="register-code"
              ref="codeInput"
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

      <div v-else class="login-success">
        <span class="success-mark" aria-hidden="true">✓</span>
        <p class="eyebrow">ACCOUNT READY</p>
        <h1 id="register-heading" ref="successHeading" tabindex="-1">注册成功</h1>
        <p>欢迎加入，{{ successName }}。</p>
        <p class="account-id">UID {{ successUid }}</p>        <RouterLink class="button button-dark" to="/account"
          >前往用户管理 <span aria-hidden="true">↗</span></RouterLink
        >
      </div>
    </div>
  </section>
</template>