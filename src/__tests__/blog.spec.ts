import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import BlogFeed from '../components/BlogFeed.vue'

const fetchMock = vi.fn()
const post = (id: number) => ({
  id,
  title: `文章 ${id}`,
  excerpt: `摘要 ${id}`,
  url: `/blog/posts/${id}`,
  category: '技术',
  publishedAt: '2026-09-12T08:00:00Z',
})
const render = (embedded = false) =>
  mount(BlogFeed, {
    props: { embedded },
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
enableAutoUnmount(afterEach)
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('blog preview', () => {
  it('requests ten posts and never renders an eleventh even when the backend returns more', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ posts: Array.from({ length: 15 }, (_, i) => post(i + 1)) }),
    })
    const wrapper = render()
    await flushPromises()
    expect(new URL(fetchMock.mock.calls[0]![0]).searchParams.get('limit')).toBe('10')
    expect(wrapper.findAll('.post-item')).toHaveLength(10)
    expect(wrapper.text()).not.toContain('文章 11')
    expect(wrapper.get('.post-link').attributes('href')).toMatch(/\/blog\/posts\/1$/)
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-09-12T08:00:00Z')
  })

  it('waits for the home blog section to approach the viewport, then fetches only once', async () => {
    let callback!: IntersectionObserverCallback
    const disconnect = vi.fn()
    const observe = vi.fn()
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(handler: IntersectionObserverCallback) {
          callback = handler
        }
        disconnect = disconnect
        observe = observe
      },
    )
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ posts: [post(1)] }) })
    const wrapper = render(true)
    expect(observe).toHaveBeenCalledWith(wrapper.element)
    expect(fetchMock).not.toHaveBeenCalled()
    callback([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver)
    expect(fetchMock).not.toHaveBeenCalled()
    callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
    await flushPromises()
    callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(disconnect).toHaveBeenCalled()
    expect(wrapper.findAll('.post-item')).toHaveLength(1)
    expect(wrapper.find('h1').exists()).toBe(false)
    expect(wrapper.get('#home-blog-heading').element.tagName).toBe('H2')
  })

  it('keeps the blog empty while the backend endpoint is not implemented', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404 })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('这里还没有文章')
    expect(wrapper.findAll('.post-item')).toHaveLength(0)
  })

  it('offers a working retry on network failure', async () => {
    fetchMock.mockRejectedValueOnce(new Error('Offline'))
    fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({ posts: [post(1)] }) })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('请稍后重试')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.findAll('.post-item')).toHaveLength(1)
  })

  it('omits malformed posts, duplicate IDs, and unsafe article links', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({
        posts: [
          post(1),
          post(1),
          null,
          { ...post(2), url: 'javascript:alert(1)' },
          { ...post(3), title: '' },
        ],
      }),
    })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('.post-item')).toHaveLength(1)
  })

  it('cancels pending requests when leaving the page', () => {
    fetchMock.mockImplementation(() => new Promise(() => {}))
    const wrapper = render()
    wrapper.unmount()
    expect(fetchMock.mock.calls[0]![1].signal.aborted).toBe(true)
  })

  it('shows a retry after a request times out', async () => {
    vi.useFakeTimers()
    fetchMock.mockImplementation(
      (_url, { signal }: { signal: AbortSignal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new Error('Aborted')), { once: true })
        }),
    )
    const wrapper = render()
    await vi.advanceTimersByTimeAsync(8000)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.get('.feed-content').attributes('aria-busy')).toBe('false')
  })
})
