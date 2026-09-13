import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginPage from '../views/LoginPage.vue'

const fetchMock = vi.fn<(input: string, options?: RequestInit) => Promise<unknown>>()
enableAutoUnmount(afterEach)
beforeEach(() => {
  fetchMock.mockReset()
  // The page pings /api/auth/me on mount to learn whether a captcha is already due.
  fetchMock.mockResolvedValue({ ok: false, status: 401, headers: new Headers() })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

async function renderLogin() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  const wrapper = mount(LoginPage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

async function fillForm(wrapper: Awaited<ReturnType<typeof renderLogin>>) {
  await wrapper.get('#identity').setValue('  example@imzako.com  ')
  await wrapper.get('#password').setValue(' secret-password ')
}

/** Calls to an endpoint, ignoring the session probe the page performs on mount. */
function callsTo(url: string) {
  return fetchMock.mock.calls.filter((call) => call[0] === url)
}

function captchaResponse(id = 'captcha-1') {
  const bytes = new Uint8Array([137, 80, 78, 71]).buffer
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'X-Captcha-Id': id }),
    blob: async () => ({
      type: 'image/png',
      arrayBuffer: async () => bytes,
    }),
  }
}

function signedIn(user: Record<string, unknown> = { id: '1', username: 'example' }) {
  return {
    ok: true,
    status: 200,
    headers: new Headers(),
    json: async () => ({ user, verificationRequired: false, maskedEmail: null }),
  }
}

describe('login form and API integration', () => {
  it('validates required fields without sending a login request', async () => {
    const wrapper = await renderLogin()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入邮箱或 UID。')
    expect(wrapper.text()).toContain('请输入密码。')
    expect(callsTo('/api/auth/login')).toHaveLength(0)
  })

  it('toggles password visibility without submitting', async () => {
    const wrapper = await renderLogin()
    expect(wrapper.get('#password').attributes('type')).toBe('password')
    await wrapper.get('.password-toggle').trigger('click')
    expect(wrapper.get('#password').attributes('type')).toBe('text')
    expect(wrapper.get('.password-toggle').attributes('aria-pressed')).toBe('true')
    expect(callsTo('/api/auth/login')).toHaveLength(0)
  })

  it('submits the documented contract once and confirms a valid server response', async () => {
    let respond!: (response: unknown) => void
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/login') {
        return new Promise((resolve) => {
          respond = resolve
        })
      }
      return Promise.resolve({ ok: false, status: 401, headers: new Headers() })
    })

    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(callsTo('/api/auth/login')).toHaveLength(1)
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeDefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({
          identity: 'example@imzako.com',
          password: ' secret-password ',
        }),
      }),
    )
    respond(signedIn({ id: '1', username: 'example', displayName: '访客' }))
    await flushPromises()
    expect(wrapper.text()).toContain('登录成功')
    expect(wrapper.text()).toContain('欢迎回来，访客。')
    expect(wrapper.find('#password').exists()).toBe(false)
  })

  it.each([
    [401, '账号或密码不正确'],
    [404, '登录服务暂未开放'],
    [429, '尝试次数较多'],
    [500, '登录服务暂时不可用'],
  ])('shows a retryable error for HTTP %i', async (status, message) => {
    fetchMock.mockImplementation((url: string) =>
      url === '/api/auth/login'
        ? Promise.resolve({ ok: false, status, headers: new Headers(), json: async () => ({}) })
        : Promise.resolve({ ok: false, status: 401, headers: new Headers() }),
    )
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain(message)
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.login-success').exists()).toBe(false)
  })

  it('rejects a successful HTTP response without valid user data', async () => {
    fetchMock.mockImplementation((url: string) =>
      url === '/api/auth/login'
        ? Promise.resolve({ ok: true, status: 200, headers: new Headers(), json: async () => ({}) })
        : Promise.resolve({ ok: false, status: 401, headers: new Headers() }),
    )
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('未能确认登录结果')
  })

  it('handles offline failures and permits retrying', async () => {
    fetchMock.mockRejectedValue(new Error('Offline'))
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('请检查网络')
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
  })

  it('cancels a pending login when leaving the page', async () => {
    fetchMock.mockImplementation((url: string) =>
      url === '/api/auth/login'
        ? new Promise(() => {})
        : Promise.resolve({ ok: false, status: 401, headers: new Headers() }),
    )
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    expect(callsTo('/api/auth/login')[0]![1]?.signal?.aborted).toBe(true)
  })

  it('times out after ten seconds and restores the submit button', async () => {
    vi.useFakeTimers()
    fetchMock.mockImplementation(
      (url: string, init?: RequestInit) =>
        url === '/api/auth/login'
          ? new Promise((_resolve, reject) => {
              init?.signal?.addEventListener('abort', () => reject(new Error('Aborted')), {
                once: true,
              })
            })
          : Promise.resolve({ ok: false, status: 401, headers: new Headers() }),
    )
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await vi.advanceTimersByTimeAsync(10000)
    expect(wrapper.get('[role="alert"]').text()).toContain('已超时')
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
  })
})

describe('login captcha after repeated failures', () => {
  it('asks for a captcha, then submits its id and answer with the credentials', async () => {
    let loginCalls = 0
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/captcha') return Promise.resolve(captchaResponse('abc123'))
      if (url === '/api/auth/login') {
        loginCalls += 1
        // First attempt fails, second attempt is answered with the captcha.
        return Promise.resolve(
          loginCalls === 1
            ? {
                ok: false,
                status: 428,
                headers: new Headers(),
                json: async () => ({ code: 'CAPTCHA_REQUIRED', message: '需要输入图形验证码' }),
              }
            : signedIn({ id: '1', username: 'example' }),
        )
      }
      return Promise.resolve({ ok: false, status: 401, headers: new Headers() })
    })

    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    // The captcha image travels through a FileReader, so let that settle too.
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('需要输入图形验证码')
    expect(wrapper.find('#captcha-answer').exists()).toBe(true)
    expect(callsTo('/api/auth/captcha')).toHaveLength(1)
    expect(wrapper.get('.captcha-image').attributes('src')).toMatch(/^data:image\/png/)

    // Submitting without the answer is refused locally.
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('请输入图形验证码。')
    expect(callsTo('/api/auth/login')).toHaveLength(1)

    await wrapper.get('#captcha-answer').setValue('ab12')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(callsTo('/api/auth/login')).toHaveLength(2)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({
        body: JSON.stringify({
          identity: 'example@imzako.com',
          password: ' secret-password ',
          captchaId: 'abc123',
          captchaAnswer: 'ab12',
        }),
      }),
    )
    expect(wrapper.text()).toContain('登录成功')
  })

  it('refreshes the challenge when the answer is rejected', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/captcha') return Promise.resolve(captchaResponse('fresh-id'))
      if (url === '/api/auth/login') {
        return Promise.resolve({
          ok: false,
          status: 400,
          headers: new Headers(),
          json: async () => ({ code: 'INVALID_CAPTCHA', message: '图形验证码不正确或已过期' }),
        })
      }
      return Promise.resolve({ ok: false, status: 401, headers: new Headers() })
    })

    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    // The rejected attempt asks the server for a new captcha for the next try.
    await wrapper.get('#captcha-answer').setValue('zzzz')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('图形验证码不正确或已过期')
    expect(callsTo('/api/auth/captcha').length).toBeGreaterThanOrEqual(1)
  })
})

describe('login email verification', () => {
  it('asks for the emailed code when the account is not verified', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/login') {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => ({
            user: null,
            verificationRequired: true,
            maskedEmail: 'ex*****@imzako.com',
          }),
        })
      }
      return Promise.resolve({ ok: false, status: 401, headers: new Headers() })
    })

    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('验证邮箱')
    expect(wrapper.text()).toContain('ex*****@imzako.com')
    expect(wrapper.find('#login-code').exists()).toBe(true)
    expect(wrapper.find('#password').exists()).toBe(false)
  })

  it('verifies the code and signs in', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/login') {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => ({ user: null, verificationRequired: true, maskedEmail: 'a***@b.com' }),
        })
      }
      if (url === '/api/auth/register/verify') {
        return Promise.resolve(signedIn({ id: '1', username: 'verified' }))
      }
      return Promise.resolve({ ok: false, status: 401, headers: new Headers() })
    })

    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('#login-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/register/verify',
      expect.objectContaining({
        body: JSON.stringify({ identity: 'example@imzako.com', code: '123456' }),
      }),
    )
    expect(wrapper.text()).toContain('登录成功')
  })

  it('reports a wrong code and can resend it', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/auth/login') {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: new Headers(),
          json: async () => ({ user: null, verificationRequired: true, maskedEmail: 'a***@b.com' }),
        })
      }
      if (url === '/api/auth/register/verify') {
        return Promise.resolve({
          ok: false,
          status: 400,
          headers: new Headers(),
          json: async () => ({ code: 'VERIFICATION_INVALID_CODE', message: '验证码不正确或已过期' }),
        })
      }
      if (url === '/api/auth/register/resend') {
        return Promise.resolve({ ok: true, status: 204, headers: new Headers() })
      }
      return Promise.resolve({ ok: false, status: 401, headers: new Headers() })
    })

    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('#login-code').setValue('000000')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('验证码不正确或已过期')

    await wrapper.get('.link-button').trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/register/resend',
      expect.objectContaining({ body: JSON.stringify({ identity: 'example@imzako.com' }) }),
    )
    expect(wrapper.text()).toContain('验证码已重新发送。')
  })
})
