import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import App from '../App.vue'
import { useAuth } from '../composables/useAuth'
import router from '../router'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }))
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
enableAutoUnmount(afterEach)

describe('page navigation', () => {
  // Read from the same source the app uses, so the assertion tracks .env instead of
  // hard-coding a deployment-specific URL.
  const gitUrl = import.meta.env.VITE_GIT_URL || 'https://git.imzako.com'
  const blogUrl = import.meta.env.VITE_BLOG_URL || 'http://localhost:8080/blog'

  it('uses the specified service URLs and preserves the empty blog route', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(wrapper.get('h1').text()).toContain('Connect To')
    expect(wrapper.get(`a.service-card[href="${gitUrl}"]`).attributes('href')).toBe(gitUrl)
    expect(wrapper.find(`.service-card[href="${blogUrl}"]`).exists()).toBe(true)
    expect(wrapper.get('.service-card-placeholder').element.tagName).toBe('ARTICLE')
    expect(wrapper.get('.service-card-placeholder').attributes('href')).toBeUndefined()
    expect(wrapper.find('#home-blog').exists()).toBe(true)
    expect(wrapper.get('.blog-scroll-cue').attributes('aria-controls')).toBe('home-blog')
    await router.push('/blog')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/blog')
    expect(document.title).toBe('博客')
    expect(wrapper.text()).toContain('这里还没有文章')
    expect(wrapper.get('nav a[aria-current="page"]').attributes('href')).toBe(blogUrl)

    await wrapper.get('.feed-footer a').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(document.title).toBe('站点导航')
    expect(wrapper.find('.service-grid').exists()).toBe(true)
    await wrapper.get('.header-login').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    await flushPromises()
    expect(document.title).toBe('用户登录')
    expect(wrapper.find('form').exists()).toBe(true)
    wrapper.unmount()
  })

  it('supports direct blog entry, legacy links, and unknown client routes', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    await router.push('/blog/')
    const wrapper = mount(App, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('这里还没有文章')

    await router.push('/about')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')

    await router.push('/missing')
    await flushPromises()
    expect(wrapper.text()).toContain('你访问的页面不存在')
    wrapper.unmount()
  })
})

describe('header session state', () => {
  afterEach(() => {
    useAuth().setUser(null)
  })

  it('shows the login link while signed out', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(wrapper.get('.header-login').text()).toContain('用户登录')
    expect(wrapper.find('.header-greeting').exists()).toBe(false)
    wrapper.unmount()
  })

  it('leaves the greeting collapsed by default and opens the account menu', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const { setUser } = useAuth()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })

    setUser({ id: 1000001, username: '新用户_01', avatar: 'data:image/png;base64,AAA' })
    await flushPromises()

    const greeting = wrapper.get('.header-greeting')
    expect(greeting.text()).toBe('欢迎新用户_01▾')
    expect(greeting.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.account-menu').exists()).toBe(false)

    await greeting.trigger('click')
    await flushPromises()

    expect(wrapper.get('.header-greeting').attributes('aria-expanded')).toBe('true')
    const menu = wrapper.get('.account-menu')
    expect(menu.get('.account-menu-uid').text()).toBe('UID 1000001')
    expect(menu.get('a.account-menu-item').attributes('href')).toBe('/account')
    // The avatar shows through the badge instead of the initial.
    expect(wrapper.get('.avatar-badge img').attributes('src')).toBe('data:image/png;base64,AAA')

    await menu.get('button.account-menu-signout').trigger('click')
    await flushPromises()

    expect(wrapper.find('.header-greeting').exists()).toBe(false)
    expect(wrapper.find('.account-menu').exists()).toBe(false)
    expect(wrapper.find('.header-login').exists()).toBe(true)
    wrapper.unmount()
  })

  it('falls back to the account initial when no avatar is stored', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const { setUser } = useAuth()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })

    setUser({ id: 1000002, username: 'cherry', avatar: null })
    await flushPromises()

    expect(wrapper.get('.avatar-badge').text()).toBe('C')
    expect(wrapper.find('.avatar-badge img').exists()).toBe(false)
    wrapper.unmount()
  })
})
