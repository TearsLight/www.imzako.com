export const siteLinks = {
  git: publicUrl(import.meta.env.VITE_GIT_URL, 'https://git.imzako.com'),
  blog: publicUrl(import.meta.env.VITE_BLOG_URL, 'http://localhost:8080/blog'),
} as const

export type SiteEntry = {
  id: string
  name: string
  label: string
  description: string
  address?: string
  href?: string
  icon: 'git' | 'blog' | 'plus'
}

type ExtraSite = Pick<SiteEntry, 'name' | 'label' | 'description' | 'address'> & {
  href: string
}

function publicUrl(value: string | undefined, fallback: string) {
  if (!value) return fallback
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? value : fallback
  } catch {
    return fallback
  }
}

function displayAddress(value: string) {
  return value.replace(/^https?:\/\//, '').replace(/\/$/, '')
}

function extraSites(value: string | undefined): SiteEntry[] {
  if (!value) return []
  try {
    const configured: unknown = JSON.parse(value)
    if (!Array.isArray(configured)) return []
    return configured.flatMap((entry, index) => {
      if (!entry || typeof entry !== 'object') return []
      const candidate = entry as Partial<ExtraSite>
      if (
        typeof candidate.name !== 'string' ||
        typeof candidate.label !== 'string' ||
        typeof candidate.description !== 'string' ||
        typeof candidate.href !== 'string'
      ) {
        return []
      }
      const href = publicUrl(candidate.href, '')
      if (!href) return []
      return [
        {
          id: `extra-${index + 1}`,
          name: candidate.name,
          label: candidate.label,
          description: candidate.description,
          address: typeof candidate.address === 'string' ? candidate.address : displayAddress(href),
          href,
          icon: 'plus' as const,
        },
      ]
    })
  } catch {
    return []
  }
}

const configuredSites = extraSites(import.meta.env.VITE_EXTRA_SITES_JSON)

export const siteName = import.meta.env.VITE_SITE_NAME?.trim() || '樱桃灯笼'
export const publicDomain = import.meta.env.VITE_PUBLIC_DOMAIN?.trim() || 'www.imzako.com'

// Add a site here to extend the homepage navigation. Entries without href are placeholders.
export const sites: SiteEntry[] = [
  {
    id: 'git',
    name: 'Git',
    label: 'CODE & COLLABORATE',
    description: '托管代码，管理版本。',
    address: displayAddress(siteLinks.git),
    href: siteLinks.git,
    icon: 'git',
  },
  {
    id: 'blog',
    name: '博客',
    label: 'THOUGHTS & STORIES',
    description: '博客空间',
    address: displayAddress(siteLinks.blog),
    href: siteLinks.blog,
    icon: 'blog',
  },
  ...configuredSites,
  {
    id: 'more',
    name: '更多站点',
    label: 'MORE TO COME',
    description: '占位',
    icon: 'plus',
  },
]
