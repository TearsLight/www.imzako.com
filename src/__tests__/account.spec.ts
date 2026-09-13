import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AccountPage from '../views/AccountPage.vue'
import { useAuth } from '../composables/useAuth'

const prepareAvatar = vi.fn()
vi.mock('../services/avatar', () => ({
  prepareAvatar: (file: File) => prepareAvatar(file),
  AvatarError: class AvatarError extends Error {},
}))

const fetchMock = vi.fn()
enableAutoUnmount(afterEach)
beforeEach(() => {
  fetchMock.mockReset()
  prepareAvatar.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  useAuth().setUser({
    id: 1000001,
    username: 'cherry',
    email: 'lanterns1020@gmail.com',
    avatar: null,
  })
})
afterEach(() => {
  useAuth().setUser(null)
  vi.unstubAllGlobals()
})

async function renderAccount() {
  const blank = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: blank },
      { path: '/login', component: blank },
      { path: '/account', component: blank },
    ],
  })
  await router.push('/account')
  return mount(AccountPage, { global: { plugins: [router] } })
}

function userResponse(overrides: Record<string, unknown> = {}) {
  return {
    ok: true,
    status: 200,
    json: async () => ({
      user: {
        id: 1000001,
        username: 'cherry',
        displayName: 'cherry',
        email: 'lanterns1020@gmail.com',
        avatar: null,
        roles: ['USER'],
      },
      ...overrides,
    }),
  }
}

/** Several independent forms share this page, so target them by their marker. */
function submitForm(wrapper: Awaited<ReturnType<typeof renderAccount>>, name: string) {
  return wrapper.get(`form[data-form="${name}"]`).trigger('submit')
}

describe('account management page', () => {
  it('shows all four management sections for a signed-in user', async () => {
    const wrapper = await renderAccount()

    expect(wrapper.get('#account-heading').text()).toBe('cherry')
    expect(wrapper.text()).toContain('UID 1000001')
    expect(wrapper.find('#avatar-heading').exists()).toBe(true)
    expect(wrapper.find('#username-heading').exists()).toBe(true)
    expect(wrapper.find('#email-heading').exists()).toBe(true)
    expect(wrapper.find('#password-heading').exists()).toBe(true)
  })

  it('prompts for sign-in when nobody is signed in', async () => {
    useAuth().setUser(null)
    const wrapper = await renderAccount()

    expect(wrapper.text()).toContain('需要登录')
    expect(wrapper.find('#avatar-heading').exists()).toBe(false)
  })

  it('renames the account and keeps the header greeting in sync', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        user: { id: 1000001, username: '新名字', email: 'lanterns1020@gmail.com' },
      }),
    })
    const wrapper = await renderAccount()

    await wrapper.get('#account-username').setValue('新名字')
    await submitForm(wrapper, 'username')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/username',
      expect.objectContaining({
        method: 'PUT',
        credentials: 'include',
        body: JSON.stringify({ username: '新名字' }),
      }),
    )
    expect(wrapper.text()).toContain('用户名已更新。')
    expect(useAuth().greeting.value).toBe('欢迎新名字')
  })

  it('rejects an invalid username before sending a request', async () => {
    const wrapper = await renderAccount()

    await wrapper.get('#account-username').setValue('bad name')
    await submitForm(wrapper, 'username')

    expect(wrapper.text()).toContain('用户名只能包含')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('uploads a prepared avatar as a data URI', async () => {
    prepareAvatar.mockResolvedValue('data:image/jpeg;base64,ZZZ')
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        user: { id: 1000001, username: 'cherry', avatar: 'data:image/jpeg;base64,ZZZ' },
      }),
    })
    const wrapper = await renderAccount()

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['x'], 'a.png', { type: 'image/png' })],
    })
    await input.trigger('change')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/avatar',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ avatar: 'data:image/jpeg;base64,ZZZ' }),
      }),
    )
    expect(wrapper.text()).toContain('头像已更新。')
    expect(useAuth().avatar.value).toBe('data:image/jpeg;base64,ZZZ')
  })

  it('clears the avatar and surfaces preparation failures', async () => {
    useAuth().setUser({ id: 1000001, username: 'cherry', avatar: 'data:image/jpeg;base64,OLD' })
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ user: { id: 1000001, username: 'cherry', avatar: null } }),
    })
    const wrapper = await renderAccount()

    await wrapper.get('.button-outline').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/avatar',
      expect.objectContaining({ body: JSON.stringify({ avatar: null }) }),
    )
    expect(wrapper.text()).toContain('头像已移除。')
  })

  it('reports the server message when the chosen image cannot be processed', async () => {
    const { AvatarError } = await import('../services/avatar')
    prepareAvatar.mockRejectedValue(new AvatarError('图片不能超过 5 MB。'))
    const wrapper = await renderAccount()

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['x'], 'big.png', { type: 'image/png' })],
    })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('图片不能超过 5 MB。')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('requires the current password to rebind the email', async () => {
    const wrapper = await renderAccount()

    await wrapper.get('#account-email').setValue('new@example.com')
    await submitForm(wrapper, 'email')
    await flushPromises()

    expect(wrapper.text()).toContain('请输入当前密码以确认改绑。')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rebinds the email with the current password', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        user: { id: 1000001, username: 'cherry', email: 'new@example.com' },
      }),
    })
    const wrapper = await renderAccount()

    await wrapper.get('#account-email').setValue('new@example.com')
    await wrapper.get('#account-email-password').setValue('current-secret')
    await submitForm(wrapper, 'email')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/email',
      expect.objectContaining({
        body: JSON.stringify({ currentPassword: 'current-secret', email: 'new@example.com' }),
      }),
    )
    expect(wrapper.text()).toContain('邮箱已改绑')
  })

  it('keeps the current email until the replacement address is verified', async () => {
    fetchMock
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          user: null,
          verificationRequired: true,
          maskedEmail: 'ne***@example.com',
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          user: { id: 1000001, username: 'cherry', email: 'new@example.com' },
          verificationRequired: false,
          maskedEmail: null,
        }),
      })
    const wrapper = await renderAccount()

    await wrapper.get('#account-email').setValue('new@example.com')
    await wrapper.get('#account-email-password').setValue('current-secret')
    await submitForm(wrapper, 'email')
    await flushPromises()

    expect(wrapper.text()).toContain('ne***@example.com')
    expect(wrapper.find('form[data-form="email-verification"]').exists()).toBe(true)
    expect(useAuth().currentUser.value?.email).toBe('lanterns1020@gmail.com')

    await wrapper.get('#account-email-code').setValue('123456')
    await submitForm(wrapper, 'email-verification')
    await flushPromises()

    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/auth/email/verify',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ code: '123456' }) }),
    )
    expect(useAuth().currentUser.value?.email).toBe('new@example.com')
    expect(wrapper.text()).toContain('新邮箱已验证并完成改绑')
  })

  it('surfaces a conflict when the new email is already registered', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ code: 'ACCOUNT_EXISTS', message: '邮箱已被注册' }),
    })
    const wrapper = await renderAccount()

    await wrapper.get('#account-email').setValue('taken@example.com')
    await wrapper.get('#account-email-password').setValue('current-secret')
    await submitForm(wrapper, 'email')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('邮箱已被注册')
  })

  it('validates the new password before sending a request', async () => {
    const wrapper = await renderAccount()

    await wrapper.get('#account-current-password').setValue('current-secret')
    await wrapper.get('#account-new-password').setValue('short')
    await wrapper.get('#account-new-password-confirmation').setValue('short')
    await submitForm(wrapper, 'password')

    expect(wrapper.text()).toContain('新密码长度应为 8–128 个字符。')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('signs the user out locally after a password change', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204 })
    const wrapper = await renderAccount()

    await wrapper.get('#account-current-password').setValue('current-secret')
    await wrapper.get('#account-new-password').setValue('a-brand-new-password')
    await wrapper.get('#account-new-password-confirmation').setValue('a-brand-new-password')
    await submitForm(wrapper, 'password')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/password',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          currentPassword: 'current-secret',
          newPassword: 'a-brand-new-password',
        }),
      }),
    )
    expect(wrapper.text()).toContain('密码已修改')
    expect(useAuth().isAuthenticated.value).toBe(false)
  })
})
