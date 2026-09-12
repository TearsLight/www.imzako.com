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
    await wrapper.get('#register-birthday').setValue('2999-01-01')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('请输入有效的邮箱地址。')
    expect(wrapper.text()).toContain('用户名只能包含')
    expect(wrapper.text()).toContain('密码长度应为 8–128 个字符。')
    expect(wrapper.text()).toContain('两次输入的密码不一致。')
    expect(wrapper.text()).toContain('生日必须早于今天。')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('submits the public registration contract and keeps the password untrimmed', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({
        user: {
          id: 1000001,
          username: '新用户_01',
          displayName: '新用户_01',
          email: 'member@example.com',
          roles: ['USER'],
        },
      }),
    })
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('#register-gender').setValue('other')
    await wrapper.get('#register-birthday').setValue('2000-01-02')
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
          gender: 'other',
          birthday: '2000-01-02',
        }),
      }),
    )
    expect(wrapper.text()).toContain('注册成功')
    expect(wrapper.text()).toContain('UID 1000001')
    expect(wrapper.find('#register-password').exists()).toBe(false)
  })

  it('sends optional profile fields with safe defaults', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({ user: { id: 1000002, username: '新用户_01' } }),
    })
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual(
      expect.objectContaining({ gender: 'undisclosed', birthday: null }),
    )
  })

  it.each([
    [409, '用户名或邮箱已被注册'],
    [503, '账号服务暂时不可用'],
    [500, '注册服务暂时不可用'],
  ])('shows a useful retryable error for HTTP %i', async (status, message) => {
    fetchMock.mockResolvedValue({ ok: false, status })
    const wrapper = await renderRegister()
    await fillRequiredFields(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(message)
    expect(wrapper.get('.login-submit').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.login-success').exists()).toBe(false)
  })
})
