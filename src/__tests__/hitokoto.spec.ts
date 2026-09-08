import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import HitokotoFooter from '../components/HitokotoFooter.vue'

const sentence = {
  hitokoto: '用代码表达言语的魅力。',
  uuid: '75a45fd4-4f2f-45eb-80cb-6f0a7bcdfaf2',
  from: '一言开发者中心',
}
const cacheKey = 'imzako:hitokoto:v1'
const fetchMock = vi.fn()

beforeEach(() => {
  localStorage.clear()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('Hitokoto footer', () => {
  it('displays a fetched sentence, links to its source, and caches it across remounts', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => sentence })
    const wrapper = mount(HitokotoFooter)
    await flushPromises()
    expect(wrapper.get('a').text()).toContain(sentence.hitokoto)
    expect(wrapper.get('a').attributes('href')).toBe(`https://hitokoto.cn?uuid=${sentence.uuid}`)
    expect(wrapper.get('a').attributes('rel')).toBe('noopener noreferrer')
    wrapper.unmount()
    const remounted = mount(HitokotoFooter)
    await flushPromises()
    expect(remounted.text()).toContain(sentence.hitokoto)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('retains an expired cached sentence when the API is offline', async () => {
    localStorage.setItem(cacheKey, JSON.stringify({ sentence, expiresAt: Date.now() - 1 }))
    fetchMock.mockRejectedValue(new Error('Offline'))
    const wrapper = mount(HitokotoFooter)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(wrapper.get('a').text()).toContain(sentence.hitokoto)
  })

  it('refreshes expired or corrupt cache entries', async () => {
    localStorage.setItem(cacheKey, '{invalid')
    fetchMock.mockResolvedValue({ ok: true, json: async () => sentence })
    const wrapper = mount(HitokotoFooter)
    await flushPromises()
    expect(wrapper.text()).toContain(sentence.hitokoto)
    expect(JSON.parse(localStorage.getItem(cacheKey)!).expiresAt).toBeGreaterThan(Date.now())

    wrapper.unmount()
    localStorage.setItem(cacheKey, JSON.stringify({ sentence, expiresAt: 0 }))
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ ...sentence, hitokoto: '新的句子。' }),
    })
    const remounted = mount(HitokotoFooter)
    await flushPromises()
    expect(remounted.text()).toContain('新的句子。')
  })

  it.each([
    { ok: false, json: async () => sentence },
    { ok: true, json: async () => ({ hitokoto: '', uuid: 'invalid' }) },
    {
      ok: true,
      json: async () => {
        throw new Error('Invalid JSON')
      },
    },
  ])(
    'keeps the fallback without a misleading source link on invalid responses: %#',
    async (response) => {
      fetchMock.mockResolvedValue(response)
      const wrapper = mount(HitokotoFooter)
      await flushPromises()
      expect(wrapper.text()).toBe('在自己的节奏里，持续创造')
      expect(wrapper.find('a').exists()).toBe(false)
      expect(localStorage.getItem(cacheKey)).toBeNull()
    },
  )

  it('still displays the API response when browser storage is disabled', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Disabled')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Disabled')
    })
    fetchMock.mockResolvedValue({ ok: true, json: async () => sentence })
    const wrapper = mount(HitokotoFooter)
    await flushPromises()
    expect(wrapper.get('a').text()).toContain(sentence.hitokoto)
  })

  it('aborts slow requests after five seconds', async () => {
    vi.useFakeTimers()
    fetchMock.mockImplementation(
      (_url, { signal }: { signal: AbortSignal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new Error('Aborted')), { once: true })
        }),
    )
    const wrapper = mount(HitokotoFooter)
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetchMock.mock.calls[0]![1].signal.aborted).toBe(true)
    expect(wrapper.text()).toBe('在自己的节奏里，持续创造')
  })

  it('aborts pending requests when the component unmounts', () => {
    fetchMock.mockImplementation(() => new Promise(() => {}))
    const wrapper = mount(HitokotoFooter)
    wrapper.unmount()
    expect(fetchMock.mock.calls[0]![1].signal.aborted).toBe(true)
  })
})
