import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginPage from '../views/LoginPage.vue'

const fetchMock = vi.fn()
enableAutoUnmount(afterEach)
beforeEach(() => {
  fetchMock.mockReset()
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
  return mount(LoginPage, { global: { plugins: [router] } })
}

async function fillForm(wrapper: Awaited<ReturnType<typeof renderLogin>>) {
  await wrapper.get('#username').setValue('  example  ')
  await wrapper.get('#password').setValue(' secret-password ')
}

describe('login form and API integration', () => {
  it('validates required fields without sending an empty request', async () => {
    const wrapper = await renderLogin()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入账号。')
    expect(wrapper.text()).toContain('请输入密码。')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('toggles password visibility without submitting', async () => {
    const wrapper = await renderLogin()
    expect(wrapper.get('#password').attributes('type')).toBe('password')
    await wrapper.get('.password-toggle').trigger('click')
    expect(wrapper.get('#password').attributes('type')).toBe('text')
    expect(wrapper.get('.password-toggle').attributes('aria-pressed')).toBe('true')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('submits the documented contract once and confirms a valid server response', async () => {
    let respond!: (response: unknown) => void
    fetchMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          respond = resolve
        }),
    )
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeDefined()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({ username: 'example', password: ' secret-password ' }),
      }),
    )
    respond({
      ok: true,
      json: async () => ({ user: { id: '1', username: 'example', displayName: '访客' } }),
    })
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
    fetchMock.mockResolvedValue({ ok: false, status })
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain(message)
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.login-success').exists()).toBe(false)
  })

  it('rejects a successful HTTP response without valid user data', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ success: true }) })
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
    fetchMock.mockImplementation(() => new Promise(() => {}))
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    expect(fetchMock.mock.calls[0]![1].signal.aborted).toBe(true)
  })

  it('times out after ten seconds and restores the submit button', async () => {
    vi.useFakeTimers()
    fetchMock.mockImplementation(
      (_url, { signal }: { signal: AbortSignal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new Error('Aborted')), { once: true })
        }),
    )
    const wrapper = await renderLogin()
    await fillForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await vi.advanceTimersByTimeAsync(10000)
    expect(wrapper.get('[role="alert"]').text()).toContain('已超时')
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
  })
})
