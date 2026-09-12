import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
// The common build covers the languages most often used in technical writing
// without shipping every niche grammar to each article reader.
import hljs from 'highlight.js/lib/common'

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  highlight(code, language) {
    const requested = language.trim().toLowerCase()
    if (requested && hljs.getLanguage(requested)) {
      return `<pre class="hljs"><code class="language-${requested}">${hljs.highlight(code, { language: requested, ignoreIllegals: true }).value}</code></pre>`
    }
    return `<pre class="hljs"><code>${hljs.highlightAuto(code).value}</code></pre>`
  },
})

// Resolve links relative to the source .md file, not the browser's current route.
function fileLink(value: string, slug: string, image: boolean, revision: string): string {
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#|\/)/i.test(value)) return value
  const sourcePath = slug.split('/').map(encodeURIComponent).join('/')
  const target = new URL(value, `https://markdown.invalid/${sourcePath}.md`)
  if (image) {
    return `/api/blog/assets?${new URLSearchParams({ path: decodeURIComponent(target.pathname.slice(1)), v: revision })}`
  }
  if (/\.md$/i.test(target.pathname)) {
    return `/blog/posts/${target.pathname.slice(1, -3)}${target.hash}`
  }
  return value
}

export function renderMarkdown(source: string, slug: string, revision = ''): string {
  const tokens = markdown.parse(source, {})
  for (const token of tokens) {
    for (const child of token.children ?? []) {
      const attribute = child.type === 'image' ? 'src' : child.type === 'link_open' ? 'href' : null
      const value = attribute ? child.attrGet(attribute) : null
      if (attribute && value) {
        try {
          child.attrSet(attribute, fileLink(String(value), slug, child.type === 'image', revision))
        } catch {
          child.attrSet(attribute, '')
        }
      }
      if (child.type === 'image') child.attrSet('loading', 'lazy')
    }
  }
  return DOMPurify.sanitize(markdown.renderer.render(tokens, markdown.options, {}), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button'],
    FORBID_ATTR: ['style'],
  })
}
