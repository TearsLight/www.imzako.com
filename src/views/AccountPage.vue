<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  AuthError,
  updateAvatar,
  updateEmail,
  updatePassword,
  updateUsername,
  verifyEmailChange,
} from '../services/auth'
import { AvatarError, prepareAvatar } from '../services/avatar'
import { accountInitial, accountName, useAuth } from '../composables/useAuth'

const { currentUser, avatar, initial, setUser, signOut } = useAuth()

const fileInput = ref<HTMLInputElement | null>(null)
const avatarBusy = ref(false)
const avatarNotice = ref('')
const avatarError = ref('')

const username = ref(currentUser.value?.username ?? '')
const usernameBusy = ref(false)
const usernameNotice = ref('')
const usernameError = ref('')

const email = ref(currentUser.value?.email ?? '')
const emailPassword = ref('')
const emailBusy = ref(false)
const emailNotice = ref('')
const emailError = ref('')
const emailVerificationRequired = ref(false)
const pendingEmailMask = ref('')
const emailCode = ref('')

const currentPassword = ref('')
const newPassword = ref('')
const newPasswordConfirmation = ref('')
const passwordBusy = ref(false)
const passwordNotice = ref('')
const passwordError = ref('')
/** After a password change the server ends the session, so the UI switches to a sign-in prompt. */
const signedOutAfterPasswordChange = ref(false)

const usernameChanged = computed(
  () => username.value.trim() !== (currentUser.value?.username ?? ''),
)
const emailChanged = computed(
  () => email.value.trim().toLowerCase() !== (currentUser.value?.email ?? '').toLowerCase(),
)
const emailValid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim()))

function fail(cause: unknown, fallback: string) {
  return cause instanceof AuthError || cause instanceof AvatarError ? cause.message : fallback
}

async function chooseAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return

  avatarError.value = ''
  avatarNotice.value = ''
  avatarBusy.value = true
  try {
    const dataUri = await prepareAvatar(file)
    const result = await updateAvatar(dataUri)
    setUser(result.user)
    avatarNotice.value = '头像已更新。'
  } catch (cause) {
    avatarError.value = fail(cause, '头像更新失败，请稍后重试。')
  } finally {
    avatarBusy.value = false
  }
}

async function removeAvatar() {
  avatarError.value = ''
  avatarNotice.value = ''
  avatarBusy.value = true
  try {
    const result = await updateAvatar(null)
    setUser(result.user)
    avatarNotice.value = '头像已移除。'
  } catch (cause) {
    avatarError.value = fail(cause, '头像更新失败，请稍后重试。')
  } finally {
    avatarBusy.value = false
  }
}

async function saveUsername() {
  usernameError.value = ''
  usernameNotice.value = ''
  const value = username.value.trim()
  if (value.length < 3 || value.length > 32) {
    usernameError.value = '用户名长度应为 3–32 个字符。'
    return
  }
  if (!/^[\p{L}\p{N}_.-]+$/u.test(value)) {
    usernameError.value = '用户名只能包含文字、数字、点、横线和下划线。'
    return
  }

  usernameBusy.value = true
  try {
    const result = await updateUsername(value)
    setUser(result.user)
    usernameNotice.value = '用户名已更新。'
  } catch (cause) {
    usernameError.value = fail(cause, '用户名更新失败，请稍后重试。')
  } finally {
    usernameBusy.value = false
  }
}

async function saveEmail() {
  emailError.value = ''
  emailNotice.value = ''
  if (!emailValid.value) {
    emailError.value = '请输入有效的邮箱地址。'
    return
  }
  if (!emailPassword.value) {
    emailError.value = '请输入当前密码以确认改绑。'
    return
  }

  emailBusy.value = true
  try {
    const result = await updateEmail(emailPassword.value, email.value.trim())
    emailPassword.value = ''
    if (result.verificationRequired) {
      emailVerificationRequired.value = true
      pendingEmailMask.value = result.maskedEmail ?? email.value.trim()
      emailNotice.value = '验证码已发送，新邮箱验证前不会替换当前邮箱。'
    } else if (result.user) {
      setUser(result.user)
      emailNotice.value = '邮箱已改绑，下次登录请使用新邮箱。'
    }
  } catch (cause) {
    emailError.value = fail(cause, '邮箱改绑失败，请稍后重试。')
  } finally {
    emailBusy.value = false
  }
}

async function confirmEmailChange() {
  emailError.value = ''
  emailNotice.value = ''
  if (!/^\d{6}$/.test(emailCode.value.trim())) {
    emailError.value = '请输入邮件中的 6 位验证码。'
    return
  }

  emailBusy.value = true
  try {
    const result = await verifyEmailChange(emailCode.value.trim())
    if (result.user) setUser(result.user)
    emailCode.value = ''
    emailVerificationRequired.value = false
    pendingEmailMask.value = ''
    emailNotice.value = '新邮箱已验证并完成改绑。'
  } catch (cause) {
    emailError.value = fail(cause, '邮箱验证失败，请稍后重试。')
  } finally {
    emailBusy.value = false
  }
}

function cancelEmailChange() {
  emailVerificationRequired.value = false
  pendingEmailMask.value = ''
  emailCode.value = ''
  emailError.value = ''
  emailNotice.value = ''
}

async function savePassword() {
  passwordError.value = ''
  passwordNotice.value = ''
  if (!currentPassword.value) {
    passwordError.value = '请输入当前密码。'
    return
  }
  if (newPassword.value.length < 8 || newPassword.value.length > 128) {
    passwordError.value = '新密码长度应为 8–128 个字符。'
    return
  }
  if (newPassword.value !== newPasswordConfirmation.value) {
    passwordError.value = '两次输入的新密码不一致。'
    return
  }

  passwordBusy.value = true
  try {
    await updatePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    newPasswordConfirmation.value = ''
    signedOutAfterPasswordChange.value = true
    setUser(null)
  } catch (cause) {
    passwordError.value = fail(cause, '密码修改失败，请稍后重试。')
  } finally {
    passwordBusy.value = false
  }
}
</script>

<template>
  <section class="account-page" aria-labelledby="account-heading">
    <div class="account-shell">
      <RouterLink class="back-link" to="/"
        ><span aria-hidden="true">←</span> 返回站点导航</RouterLink
      >

      <template v-if="signedOutAfterPasswordChange">
        <div class="account-card">
          <span class="success-mark" aria-hidden="true">✓</span>
          <p class="eyebrow">PASSWORD CHANGED</p>
          <h1 id="account-heading">密码已修改</h1>
          <p class="account-card-intro">为安全起见当前会话已结束，请用新密码重新登录。</p>
          <RouterLink class="button button-dark" to="/login"
            >前往登录 <span aria-hidden="true">↗</span></RouterLink
          >
        </div>
      </template>

      <template v-else-if="!currentUser">
        <div class="account-card">
          <p class="eyebrow">ACCOUNT</p>
          <h1 id="account-heading">需要登录</h1>
          <p class="account-card-intro">登录后才能管理个人信息。</p>
          <RouterLink class="button button-dark" to="/login"
            >前往登录 <span aria-hidden="true">↗</span></RouterLink
          >
        </div>
      </template>

      <template v-else>
        <header class="account-head">
          <span class="account-avatar" aria-hidden="true">
            <img v-if="avatar" :src="avatar" alt="" />
            <span v-else>{{ initial }}</span>
          </span>
          <div>
            <p class="eyebrow">ACCOUNT SETTINGS</p>
            <h1 id="account-heading">{{ accountName(currentUser) }}</h1>
            <p class="account-meta">UID {{ currentUser.id }} · {{ currentUser.email }}</p>
          </div>
          <button type="button" class="account-signout" @click="signOut">退出登录</button>
        </header>

        <div class="account-grid">
          <section class="account-card" aria-labelledby="avatar-heading">
            <h2 id="avatar-heading">修改头像</h2>
            <p class="account-card-intro">
              支持 PNG、JPEG、WebP、GIF，最大 5 MB，保存时自动裁剪为 256×256。
            </p>
            <div class="avatar-row">
              <span class="account-avatar account-avatar-large" aria-hidden="true">
                <img v-if="avatar" :src="avatar" alt="" />
                <span v-else>{{ initial }}</span>
              </span>
              <div class="avatar-actions">
                <button
                  type="button"
                  class="button button-dark"
                  :disabled="avatarBusy"
                  @click="fileInput?.click()"
                >
                  {{ avatarBusy ? '正在保存…' : '选择图片' }}
                </button>
                <button
                  v-if="avatar"
                  type="button"
                  class="button button-outline"
                  :disabled="avatarBusy"
                  @click="removeAvatar"
                >
                  移除头像
                </button>
              </div>
            </div>
            <input
              ref="fileInput"
              class="visually-hidden"
              type="file"
              accept="image/*"
              @change="chooseAvatar"
            />
            <p v-if="avatarNotice" class="field-success" role="status">{{ avatarNotice }}</p>
            <p v-if="avatarError" class="field-error" role="alert">{{ avatarError }}</p>
          </section>

          <section class="account-card" aria-labelledby="username-heading">
            <h2 id="username-heading">修改用户名</h2>
            <p class="account-card-intro">用户名只用于展示，可以重复，不能用于登录。</p>
            <form
              novalidate
              class="account-form"
              data-form="username"
              @submit.prevent="saveUsername"
            >
              <div class="form-field">
                <label for="account-username">用户名</label>
                <input
                  id="account-username"
                  v-model="username"
                  name="username"
                  type="text"
                  autocomplete="nickname"
                  autocapitalize="none"
                  :spellcheck="false"
                  minlength="3"
                  maxlength="32"
                  required
                  :disabled="usernameBusy"
                />
              </div>
              <button
                class="button button-dark"
                type="submit"
                :disabled="usernameBusy || !usernameChanged"
              >
                {{ usernameBusy ? '正在保存…' : '保存用户名' }}
              </button>
            </form>
            <p v-if="usernameNotice" class="field-success" role="status">{{ usernameNotice }}</p>
            <p v-if="usernameError" class="field-error" role="alert">{{ usernameError }}</p>
          </section>

          <section class="account-card" aria-labelledby="email-heading">
            <h2 id="email-heading">改绑邮箱</h2>
            <p class="account-card-intro">
              邮箱是登录标识，必须唯一；新邮箱验证成功后才会完成改绑。
            </p>
            <form
              v-if="emailVerificationRequired"
              novalidate
              class="account-form"
              data-form="email-verification"
              @submit.prevent="confirmEmailChange"
            >
              <p class="login-notice">
                验证码已发送至 <strong>{{ pendingEmailMask }}</strong>
              </p>
              <div class="form-field">
                <label for="account-email-code">验证码</label>
                <input
                  id="account-email-code"
                  v-model="emailCode"
                  name="code"
                  type="text"
                  inputmode="numeric"
                  autocomplete="one-time-code"
                  maxlength="6"
                  placeholder="6 位数字"
                  required
                  :disabled="emailBusy"
                />
              </div>
              <div class="account-inline-actions">
                <button class="button button-dark" type="submit" :disabled="emailBusy">
                  {{ emailBusy ? '正在验证…' : '验证并改绑' }}
                </button>
                <button
                  class="button button-outline"
                  type="button"
                  :disabled="emailBusy"
                  @click="cancelEmailChange"
                >
                  返回修改
                </button>
              </div>
            </form>
            <form
              v-else
              novalidate
              class="account-form"
              data-form="email"
              @submit.prevent="saveEmail"
            >
              <div class="form-field">
                <label for="account-email">新邮箱</label>
                <input
                  id="account-email"
                  v-model="email"
                  name="email"
                  type="email"
                  autocomplete="email"
                  autocapitalize="none"
                  :spellcheck="false"
                  maxlength="254"
                  required
                  :disabled="emailBusy"
                />
              </div>
              <div class="form-field">
                <label for="account-email-password">当前密码</label>
                <input
                  id="account-email-password"
                  v-model="emailPassword"
                  name="currentPassword"
                  type="password"
                  autocomplete="current-password"
                  required
                  :disabled="emailBusy"
                />
              </div>
              <button
                class="button button-dark"
                type="submit"
                :disabled="emailBusy || !emailChanged"
              >
                {{ emailBusy ? '正在保存…' : '保存邮箱' }}
              </button>
            </form>
            <p v-if="emailNotice" class="field-success" role="status">{{ emailNotice }}</p>
            <p v-if="emailError" class="field-error" role="alert">{{ emailError }}</p>
          </section>

          <section class="account-card" aria-labelledby="password-heading">
            <h2 id="password-heading">修改密码</h2>
            <p class="account-card-intro">修改成功后会结束当前会话，需要用新密码重新登录。</p>
            <form
              novalidate
              class="account-form"
              data-form="password"
              @submit.prevent="savePassword"
            >
              <div class="form-field">
                <label for="account-current-password">当前密码</label>
                <input
                  id="account-current-password"
                  v-model="currentPassword"
                  name="currentPassword"
                  type="password"
                  autocomplete="current-password"
                  required
                  :disabled="passwordBusy"
                />
              </div>
              <div class="form-field">
                <label for="account-new-password">新密码</label>
                <input
                  id="account-new-password"
                  v-model="newPassword"
                  name="newPassword"
                  type="password"
                  autocomplete="new-password"
                  minlength="8"
                  maxlength="128"
                  placeholder="至少 8 个字符"
                  required
                  :disabled="passwordBusy"
                />
              </div>
              <div class="form-field">
                <label for="account-new-password-confirmation">确认新密码</label>
                <input
                  id="account-new-password-confirmation"
                  v-model="newPasswordConfirmation"
                  name="newPasswordConfirmation"
                  type="password"
                  autocomplete="new-password"
                  minlength="8"
                  maxlength="128"
                  required
                  :disabled="passwordBusy"
                />
              </div>
              <button class="button button-dark" type="submit" :disabled="passwordBusy">
                {{ passwordBusy ? '正在保存…' : '修改密码' }}
              </button>
            </form>
            <p v-if="passwordError" class="field-error" role="alert">{{ passwordError }}</p>
          </section>
        </div>
      </template>
    </div>
  </section>
</template>
