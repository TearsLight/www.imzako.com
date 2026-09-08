export const siteLinks = {
  git: 'https://git.imzako.com',
  blog: 'http://localhost:8080/blog',
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

// Add a site here to extend the homepage navigation. Entries without href are placeholders.
export const sites: SiteEntry[] = [
  {
    id: 'git',
    name: 'Git',
    label: 'CODE & COLLABORATE',
    description: '托管代码，管理版本，让每一个想法持续迭代。',
    address: 'git.imzako.com',
    href: siteLinks.git,
    icon: 'git',
  },
  {
    id: 'blog',
    name: '博客',
    label: 'THOUGHTS & STORIES',
    description: '记录技术探索，分享日常思考，留住每一刻灵感。',
    address: 'localhost:8080/blog',
    href: siteLinks.blog,
    icon: 'blog',
  },
  {
    id: 'more',
    name: '更多站点',
    label: 'MORE TO COME',
    description: '为新的工具与想法，留一个位置。',
    icon: 'plus',
  },
]
