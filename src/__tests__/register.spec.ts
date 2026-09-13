import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import RegisterPage from '../views/RegisterPage.vue'

const fetchMock = vi.fn()
enableAutoUnmount(afterEach)

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function renderRegister() {
  const blank = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: blank },
      { path: '/login', component: blank },
      { path: '/register', component: blank },
    ],
  })
  await router.push('/register')
  return mount(RegisterPage, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
}

async function fillRequiredFields(wrapper: Awaited<ReturnType<typeof renderRegister>>) {
  await wrapper.get('#register-email').setValue('  member@example.com ')
  await wrapper.get('#register-username').setValue('  新用户_01  ')
  await wrapper.get('#register-password').setValue(' private-password ')
  await wrapper.get('#register-password-confirmation').setValue(' private-password ')
}

function verificationRequired(maskedEmail = 'me*****@example.com') {
  return {
    ok: true,
    status: 201,
    headers: new Headers(),
    json: async () => ({ user: null, verificationRequired: true, maskedEmail }),
  }
}

describe('public registration form', () => {
  it('validates required fields and focuses the first invalid input', async () => {
    const wrapper = await renderRegister()
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('请输入邮箱。')
    expect(wrapper.text()).toContain('请输入用户名。')
    expect(wrapper.text()).toContain('请输入密码。')
    expect(document.activeElement).toBe(wrapper.get('#register-email').element)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects invalid account fields before sending a request', async () => {
    const wrapper = await renderRegister()
    await wrapper.get('#register-email').setValue('invalid-email')
    await wrapper.get('#register-username').setValue('bad name')
    await wrapper.get('#register-password').setValue('short')
    await wrapper.get('#register-password-confirmation').setValue('different')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('请输入有效的邮箱地址。')
    expect(wrapper.text()).toContain('用户名只能包含')
    expect(wrapper.text()).toContain('密码长度应为 8–128 个字符。')
    expect(wrapper.text()).toContain('两次输入的密码不一致。')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('submits the public registration contract and keeps the password untrimmed', async () => {
    fetchMock.mockResolvedValue(verificationRequired())
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/register',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({
          email: 'member@example.com',
          username: '新用户_01',
          password: ' private-password ',
        }),
      }),
    )
  })

  it('sends only the three required registration fields', async () => {
    fetchMock.mockResolvedValue(verificationRequired())
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(Object.keys(JSON.parse(fetchMock.mock.calls[0]![1].body)).sort()).toEqual([
      'email',
      'password',
      'username',
    ])
  })

  it('asks for the emailed code once the account exists', async () => {
    fetchMock.mockResolvedValue(verificationRequired('me*****@example.com'))
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('输入验证码')
    expect(wrapper.text()).toContain('me*****@example.com')
    expect(wrapper.find('#register-code').exists()).toBe(true)
    // The account part is done, so the password fields are gone.
    expect(wrapper.find('#register-password').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('#register-code').element)
  })

  it('verifies the code and signs in', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/register') return Promise.resolve(verificationRequired())
      if (url === '/api/auth/register/verify') {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => ({
            user: { id: 1000002, username: '新用户_01', email: 'member@example.com' },
            verificationRequired: false,
            maskedEmail: null,
          }),
        })
      }
      return Promise.resolve({ ok: false, status: 404, headers: new Headers() })
    })

    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('#register-code').setValue('654321')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/register/verify',
      expect.objectContaining({
        body: JSON.stringify({ identity: 'member@example.com', code: '654321' }),
      }),
    )
    expect(wrapper.text()).toContain('注册成功')
    expect(wrapper.text()).toContain('UID 1000002')
  })

  it('refuses an empty code and reports a rejected one', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/register') return Promise.resolve(verificationRequired())
      if (url === '/api/auth/register/verify') {
        return Promise.resolve({
          ok: false,
          status: 400,
          headers: new Headers(),
          json: async () => ({ code: 'VERIFICATION_INVALID_CODE', message: '验证码不正确或已过期' }),
        })
      }
      return Promise.resolve({ ok: false, status: 404, headers: new Headers() })
    })

    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('请输入邮件中的验证码。')

    await wrapper.get('#register-code').setValue('111111')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('验证码不正确或已过期')
  })

  it('can resend the code', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/register') return Promise.resolve(verificationRequired())
      if (url === '/api/auth/register/resend') {
        return Promise.resolve({ ok: true, status: 204, headers: new Headers() })
      }
      return Promise.resolve({ ok: false, status: 404, headers: new Headers() })
    })

    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('.link-button').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/register/resend',
      expect.objectContaining({ body: JSON.stringify({ identity: 'member@example.com' }) }),
    )
    expect(wrapper.text()).toContain('验证码已重新发送。')
  })

  it.each([
    [409, '邮箱已被注册'],
    [503, '账号服务暂时不可用'],
    [500, '注册服务暂时不可用'],
  ])('shows a useful retryable error for HTTP %i', async (status, message) => {
    fetchMock.mockResolvedValue({ ok: false, status, headers: new Headers() })
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(message)
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
  })
})
