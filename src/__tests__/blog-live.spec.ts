import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import BlogFeed from '../components/BlogFeed.vue'
import BlogPostPage from '../views/BlogPostPage.vue'
import { subscribeBlogUpdates } from '../services/blogUpdates'

class Events extends EventTarget {
  static connections: Events[] = []
  close = vi.fn<() => void>()
  constructor(public url: string) {
    super()
    Events.connections.push(this)
  }
}
const fetchMock = vi.fn<(input: string, options?: RequestInit) => Promise<unknown>>()
const article = (title: string, markdown = `# ${title}\n\n**Rendered** body.`) => ({
  post: {
    id: 'notes/hello',
    title,
    url: '/blog/posts/notes/hello',
    publishedAt: '2026-09-12T08:00:00Z',
    category: 'notes',
  },
  markdown,
  updatedAt: '2026-09-12T08:00:00Z',
})
const reply = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
const emit = async (name: string) => {
  Events.connections[0]!.dispatchEvent(new Event(name))
  await flushPromises()
}

beforeEach(() => {
  Events.connections = []
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('EventSource', Events)
})
enableAutoUnmount(afterEach)
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('live blog synchronization', () => {
  it('resynchronizes on first connection, changes and reconnection; releases resources on exit', () => {
    const update = vi.fn<() => void>()
    const stop = subscribeBlogUpdates(update)
    const source = Events.connections[0]!
    source.dispatchEvent(new Event('sync'))
    source.dispatchEvent(new Event('changed'))
    source.dispatchEvent(new Event('error'))
    source.dispatchEvent(new Event('sync'))
    window.dispatchEvent(new Event('online'))
    expect(update).toHaveBeenCalledTimes(4)
    expect(Events.connections).toHaveLength(1)
    stop()
    expect(source.close).toHaveBeenCalledOnce()
    window.dispatchEvent(new Event('online'))
    expect(update).toHaveBeenCalledTimes(4)
  })

  it('updates the home feed without rendering more than ten posts, and reflects deletion', async () => {
    fetchMock.mockResolvedValue(reply({ posts: [article('Before').post] }))
    const wrapper = mount(BlogFeed, {
      props: { embedded: true },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Before')
    fetchMock.mockResolvedValue(
      reply({
        posts: Array.from({ length: 12 }, (_, i) => ({
          ...article(`After ${i}`).post,
          id: String(i),
        })),
      }),
    )
    await emit('changed')
    expect(wrapper.text()).not.toContain('Before')
    expect(wrapper.findAll('.post-item')).toHaveLength(10)
    fetchMock.mockResolvedValue(reply({ posts: [] }))
    await emit('sync')
    expect(wrapper.findAll('.post-item')).toHaveLength(0)
    expect(wrapper.text()).toContain('这里还没有文章')
    wrapper.unmount()
    expect(Events.connections[0]!.close).toHaveBeenCalledOnce()
  })

  it('renders a nested Markdown article, updates its HTML and clears a deleted article on reconnect', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/blog/posts/:slug(.*)', component: BlogPostPage },
        { path: '/blog', component: BlogFeed },
      ],
    })
    await router.push('/blog/posts/notes/hello')
    fetchMock.mockResolvedValue(reply(article('Before')))
    const wrapper = mount(BlogPostPage, { global: { plugins: [router] } })
    await flushPromises()
    expect(new URL(fetchMock.mock.calls[0]![0]).searchParams.get('slug')).toBe('notes/hello')
    expect(wrapper.get('h1').text()).toBe('Before')
    expect(wrapper.get('.markdown-body strong').text()).toBe('Rendered')
    fetchMock.mockResolvedValue(
      reply(article('After', '# After\n\n## New section\n\nSaved **successfully**.')),
    )
    await emit('changed')
    expect(wrapper.get('h1').text()).toBe('After')
    expect(wrapper.get('.markdown-body h2').text()).toBe('New section')
    expect(wrapper.get('.markdown-body strong').text()).toBe('successfully')
    fetchMock.mockResolvedValue({ ok: false, status: 404 })
    await emit('sync')
    expect(wrapper.find('.article-document').exists()).toBe(false)
    expect(wrapper.text()).toContain('已被删除或移动')
    wrapper.unmount()
    expect(Events.connections[0]!.close).toHaveBeenCalledOnce()
  })

  it('keeps the current article on temporary sync failure and retries successfully', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/blog/posts/:slug(.*)', component: BlogPostPage }],
    })
    await router.push('/blog/posts/notes/hello')
    fetchMock
      .mockResolvedValueOnce(reply(article('Keep reading')))
      .mockRejectedValueOnce(new Error('Offline'))
      .mockResolvedValueOnce(reply(article('Recovered')))
    const wrapper = mount(BlogPostPage, { global: { plugins: [router] } })
    await flushPromises()
    await emit('changed')
    expect(wrapper.get('h1').text()).toBe('Keep reading')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('Recovered')
    expect(wrapper.find('.article-notice').exists()).toBe(false)
  })
})
