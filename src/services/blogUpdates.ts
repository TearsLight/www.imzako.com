// Native EventSource retries lost connections; every connection receives a full sync event.
export function subscribeBlogUpdates(onUpdate: () => void): () => void {
  if (typeof EventSource === 'undefined') return () => {}
  const source = new EventSource(import.meta.env.VITE_BLOG_EVENTS_URL || '/api/blog/events')
  const synchronize = () => onUpdate()
  source.addEventListener('sync', synchronize)
  source.addEventListener('changed', synchronize)
  // A small fallback also covers sleeping tabs and temporary proxy failures.
  const wake = () => {
    if (document.visibilityState === 'visible') onUpdate()
  }
  document.addEventListener('visibilitychange', wake)
  window.addEventListener('online', synchronize)
  return () => {
    source.removeEventListener('sync', synchronize)
    source.removeEventListener('changed', synchronize)
    source.close()
    document.removeEventListener('visibilitychange', wake)
    window.removeEventListener('online', synchronize)
  }
}
