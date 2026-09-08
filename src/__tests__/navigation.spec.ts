import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import App from '../App.vue'
import router from '../router'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Offline in navigation tests')))
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
enableAutoUnmount(afterEach)

describe('page navigation', () => {
  it('uses the specified service URLs and preserves the empty blog route', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(wrapper.get('h1').text()).toContain('从这里出发')
    expect(wrapper.get('a.service-card-featured').attributes('href')).toBe('https://git.imzako.com')
    expect(wrapper.find('.service-card[href="http://localhost:8080/blog"]').exists()).toBe(true)
    expect(wrapper.get('.service-card-placeholder').element.tagName).toBe('ARTICLE')
    expect(wrapper.get('.service-card-placeholder').attributes('href')).toBeUndefined()
    await router.push('/blog')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/blog')
    expect(document.title).toBe('博客 — imzako')
    expect(wrapper.text()).toContain('这里还没有文章')
    expect(wrapper.get('nav a[aria-current="page"]').attributes('href')).toBe(
      'http://localhost:8080/blog',
    )

    await wrapper.get('.empty-state a').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(document.title).toBe('imzako — 站点导航')
    expect(wrapper.find('.service-grid').exists()).toBe(true)
    await wrapper.get('.header-login').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    await flushPromises()
    expect(document.title).toBe('用户登录 — imzako')
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
