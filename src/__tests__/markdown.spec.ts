import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../services/markdown'

function documentFor(markdown: string, slug = 'notes/hello') {
  const element = document.createElement('div')
  element.innerHTML = renderMarkdown(markdown, slug, 'version-2')
  return element
}

describe('Markdown rendering', () => {
  it('renders headings, emphasis, lists, tables and fenced code', () => {
    const element = documentFor(
      '# Heading\n\n**Bold** and *emphasis*.\n\n- One\n- Two\n\n| Name | Value |\n| --- | --- |\n| a | b |\n\n```js\nconst x = 1\n```',
    )
    expect(element.querySelector('h1')?.textContent).toBe('Heading')
    expect(element.querySelector('strong')?.textContent).toBe('Bold')
    expect(element.querySelectorAll('li')).toHaveLength(2)
    expect(element.querySelector('table td')?.textContent).toBe('a')
    expect(element.querySelector('pre code')?.textContent).toContain('const x = 1')
    expect(element.querySelector('pre.hljs .hljs-keyword')?.textContent).toBe('const')
    expect(element.querySelector('code')?.classList.contains('language-js')).toBe(true)
  })

  it('auto-detects highlighting when the language is omitted or unknown', () => {
    const element = documentFor(
      '```\nfunction hello() { return true }\n```\n\n```unknown-language\nconst value = 1\n```',
    )
    expect(element.querySelectorAll('pre.hljs')).toHaveLength(2)
    expect(element.querySelectorAll('.hljs-keyword').length).toBeGreaterThan(0)
  })

  it('does not execute embedded HTML or javascript links', () => {
    const element = documentFor(
      '<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[bad](javascript:alert%281%29)\n\n![svg](data:image/svg+xml;base64,PHN2Zz4=)',
    )
    expect(element.querySelector('script, iframe, [onerror]')).toBeNull()
    expect(element.querySelector('a[href^="javascript:"], img[src^="data:"]')).toBeNull()
  })

  it('associates relative images and Markdown links with the source file', () => {
    const element = documentFor(
      '![图片](./images/图.png)\n\n[另一篇](../other.md)\n\n[网站](https://example.com)',
      '随笔 #1/hello world',
    )
    const image = element.querySelector('img')!
    const url = new URL(image.getAttribute('src')!, window.location.origin)
    expect(url.pathname).toBe('/api/blog/assets')
    expect(url.searchParams.get('path')).toBe('随笔 #1/images/图.png')
    expect(url.searchParams.get('v')).toBe('version-2')
    expect(image.getAttribute('loading')).toBe('lazy')
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/blog/posts/other')
    expect(element.querySelectorAll('a')[1]?.getAttribute('href')).toBe('https://example.com')
  })
})
