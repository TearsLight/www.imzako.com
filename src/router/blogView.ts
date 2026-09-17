let blogPostView: Promise<typeof import('../views/BlogPostPage.vue')> | undefined

/**
 * Keep the route lazy, but share its module request with intent preloading
 * from the blog feed. Failed chunk requests remain retryable.
 */
export function loadBlogPostView() {
  blogPostView ??= import('../views/BlogPostPage.vue').catch((error: unknown) => {
    blogPostView = undefined
    throw error
  })
  return blogPostView
}

export async function prefetchBlogPostView(): Promise<void> {
  await loadBlogPostView()
}
