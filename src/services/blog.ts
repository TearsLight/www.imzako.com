export const BLOG_PREVIEW_LIMIT = 10

export interface BlogPost {
  id: string
  title: string
  excerpt: string
  url: string
  publishedAt: string
  /** Front-matter tags joined with the first content directory. */
  category: string
  /** The same categories as a list, when the server provides them. */
  categories?: string[]
}

export interface BlogArticle {
  post: BlogPost
  markdown: string
  updatedAt: string
}

export class BlogNotFoundError extends Error {}

export async function fetchBlogArticle(slug: string, signal?: AbortSignal): Promise<BlogArticle> {
  const endpoint = new URL(
    import.meta.env.VITE_BLOG_POST_URL || '/api/blog/post',
    window.location.origin,
  )
  endpoint.searchParams.set('slug', slug)
  const response = await fetch(endpoint.href, {
    signal,
    cache: 'no-store',
    headers: { Accept: 'application/json' },
  })
  if (response.status === 404) throw new BlogNotFoundError('这篇文章已被删除或移动。')
  if (!response.ok) throw new Error('文章暂时无法加载。')
  const data: unknown = await response.json()
  if (
    !data ||
    typeof data !== 'object' ||
    !('post' in data) ||
    !('markdown' in data) ||
    typeof data.markdown !== 'string'
  )
    throw new Error('文章数据格式不正确。')
  const post = normalizePost(data.post)
  if (!post) throw new Error('文章数据格式不正确。')
  return {
    post,
    markdown: data.markdown,
    updatedAt:
      'updatedAt' in data && typeof data.updatedAt === 'string' ? data.updatedAt : post.publishedAt,
  }
}

function normalizePost(value: unknown): BlogPost | null {
  if (!value || typeof value !== 'object') return null
  const post = value as Record<string, unknown>
  if (
    (typeof post.id !== 'string' && typeof post.id !== 'number') ||
    typeof post.title !== 'string' ||
    !post.title.trim() ||
    typeof post.url !== 'string' ||
    !post.url.trim()
  )
    return null

  let url: URL
  try {
    url = new URL(post.url, window.location.origin)
  } catch {
    return null
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null

  return {
    id: String(post.id),
    title: post.title,
    excerpt: typeof post.excerpt === 'string' ? post.excerpt : '',
    url: url.href,
    publishedAt:
      typeof post.publishedAt === 'string' && Number.isFinite(Date.parse(post.publishedAt))
        ? post.publishedAt
        : '',
    category: typeof post.category === 'string' ? post.category : '',
    categories: Array.isArray(post.categories)
      ? post.categories.filter((item): item is string => typeof item === 'string' && !!item.trim())
      : undefined,
  }
}

// The file-backed API returns the most recently updated posts first.
export async function fetchBlogPosts(signal?: AbortSignal): Promise<BlogPost[]> {
  const endpoint = new URL(
    import.meta.env.VITE_BLOG_API_URL || '/api/blog/posts',
    window.location.origin,
  )
  endpoint.searchParams.set('limit', String(BLOG_PREVIEW_LIMIT))
  const response = await fetch(endpoint.href, {
    signal,
    cache: 'no-store',
    headers: { Accept: 'application/json' },
  })
  // Until the backend is connected, retain the existing empty blog state.
  if (response.status === 404 || response.status === 501) return []
  if (!response.ok) throw new Error('无法加载文章，请稍后重试。')
  const data: unknown = await response.json()
  if (!data || typeof data !== 'object' || !('posts' in data) || !Array.isArray(data.posts)) {
    throw new Error('文章数据格式不正确。')
  }
  const seen = new Set<string>()
  return data.posts
    .slice(0, BLOG_PREVIEW_LIMIT)
    .map(normalizePost)
    .filter((post): post is BlogPost => {
      if (!post || seen.has(post.id)) return false
      seen.add(post.id)
      return true
    })
}
