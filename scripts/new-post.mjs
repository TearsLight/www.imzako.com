#!/usr/bin/env node
/**
 * Creates a new blog post with its front matter filled in.
 *
 *   npm run blog -- -n "文章名称"
 *   npm run blog -- -n "文章名称" -t "技术 笔记"
 *
 * Run `npm run blog -- --help` for the full option list.
 */
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const DEFAULT_BLOG_DIRECTORY = 'blog'
/** Directory names are suggested as tags; keep the list short. */
const MAX_SUGGESTED_TAGS = 8

function printUsage() {
  console.log(`用法: npm run blog -- -n "文章名称" [选项]

选项:
  -n, --name <名称>    文章标题，同时用于生成文件名 (必填)
  -t, --tags <标签>    标签，用空格分隔，会写入 front-matter 并计入分类
  -d, --dir <子目录>   在 blog/ 下放入子目录，该子目录同样计入分类
      --dir <路径>     也可以直接传 blog/notes 这样的相对路径
  -f, --force          同名文件已存在时覆盖
  -h, --help           显示这段帮助

示例:
  npm run blog -- -n "从零实现一个解析器"
  npm run blog -- -n "并发笔记" -t "技术 Java"
  npm run blog -- -n "周记" -d notes -t "随笔"

生成的文件包含如下 front-matter，标题与日期会自动填写:

  ---
  title: 名称
  date: 2026-09-12T21:30:00+08:00
  tags: 技术 笔记
  ---
`)
}

function fail(message) {
  console.error(`错误: ${message}`)
  process.exitCode = 1
}

function parseArguments(argv) {
  const options = { name: '', tags: '', dir: '', force: false }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    const next = () => {
      const value = argv[index + 1]
      if (value === undefined || value.startsWith('-')) throw new Error(`${argument} 缺少取值`)
      index += 1
      return value
    }
    switch (argument) {
      case '-n':
      case '--name':
        options.name = next()
        break
      case '-t':
      case '--tags':
        options.tags = next()
        break
      case '-d':
      case '--dir':
        options.dir = next()
        break
      case '-f':
      case '--force':
        options.force = true
        break
      case '-h':
      case '--help':
        options.help = true
        break
      default:
        throw new Error(`无法识别的参数: ${argument}`)
    }
  }
  return options
}

/** Parses the front matter of an existing post so its tags can be suggested. */
function frontMatterTags(markdown) {
  const match = /^\uFEFF?---\s*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(markdown)
  if (!match) return []
  const tags = []
  for (const line of match[1].split(/\r?\n/)) {
    const colon = line.indexOf(':')
    if (colon <= 0) continue
    const key = line.slice(0, colon).trim().toLowerCase()
    if (key !== 'tags' && key !== 'tag') continue
    for (const token of line.slice(colon + 1).split(/[\s,，]+/)) {
      const tag = token.trim().replace(/^#+/, '')
      if (tag) tags.push(tag)
    }
  }
  return tags
}

/**
 * Suggests tags from the first level of the blog directory and from tags already
 * used in existing posts, so the author can stay consistent with the archive.
 */
async function collectSuggestedTags(blogRoot, entries) {
  const suggested = new Set()
  for (const entry of entries) {
    if (entry.isDirectory()) suggested.add(entry.name)
  }
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.toLowerCase().endsWith('.md')) continue
    try {
      const markdown = await readFile(path.join(blogRoot, entry.name), 'utf8')
      for (const tag of frontMatterTags(markdown)) suggested.add(tag)
    } catch {
      // An unreadable file simply contributes no suggestions.
    }
  }
  if (suggested.has('images')) suggested.delete('images')
  return [...suggested].slice(0, MAX_SUGGESTED_TAGS)
}

/**
 * Builds the file name from the title. Latin text becomes a dashed slug; titles
 * without any are kept verbatim, since the blog serves non-ASCII URLs fine.
 */
function toFileName(title) {
  const slug = title
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .replace(/-{2,}/g, '-')
  return slug || 'post'
}

/** Trims the tag line so the generated front matter stays on one line. */
function normalizeTags(value) {
  return value
    .split(/[\s,，]+/)
    .map((tag) => tag.trim().replace(/^#+/, ''))
    .filter(Boolean)
    .join(' ')
}

/** Local ISO timestamp with offset, e.g. 2026-09-12T21:30:00+08:00. */
function localIsoTimestamp(date) {
  const pad = (value, width = 2) => String(value).padStart(width, '0')
  const offsetMinutes = -date.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absolute = Math.abs(offsetMinutes)
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
  )
}

function buildContent(title, timestamp, tags) {
  // A placeholder heading keeps the article readable before the body is written.
  return `---\ntitle: ${title}\ndate: ${timestamp}\ntags: ${tags}\n---\n\n# ${title}\n\n`
}

async function resolveTarget(blogRoot, options) {
  const requested = options.dir.trim().replace(/^[/\\]+|[/\\]+$/g, '')
  let directory = blogRoot
  if (requested) {
    // Accept both "notes" and "blog/notes" style input.
    const withoutBlogPrefix = requested.replace(/^blog[/\\]/i, '')
    directory = path.resolve(blogRoot, withoutBlogPrefix)
    const relativeDirectory = path.relative(path.resolve(blogRoot), directory)
    if (
      relativeDirectory === '..' ||
      relativeDirectory.startsWith(`..${path.sep}`) ||
      path.isAbsolute(relativeDirectory)
    ) {
      throw new Error('子目录必须位于 blog/ 之内')
    }
  }

  const baseName = toFileName(options.name)
  let fileName = `${baseName}.md`
  if (existsSync(path.join(directory, fileName)) && !options.force) {
    // Pick a free numeric suffix instead of silently overwriting.
    let suffix = 2
    while (existsSync(path.join(directory, `${baseName}-${suffix}.md`))) suffix += 1
    fileName = `${baseName}-${suffix}.md`
  }
  return { directory, filePath: path.join(directory, fileName), fileName }
}

async function main() {
  let options
  try {
    options = parseArguments(process.argv.slice(2))
  } catch (error) {
    fail(error.message)
    printUsage()
    return
  }
  if (options.help) {
    printUsage()
    return
  }
  if (!options.name.trim()) {
    fail('必须通过 -n/--name 指定文章名称')
    printUsage()
    return
  }

  const blogRoot = path.resolve(process.env.BLOG_DIRECTORY?.trim() || DEFAULT_BLOG_DIRECTORY)
  await mkdir(blogRoot, { recursive: true })
  const entries = await readdir(blogRoot, { withFileTypes: true })

  const target = await resolveTarget(blogRoot, options)

  // Keep user input on one front-matter line so it cannot inject extra fields.
  const title = options.name.replace(/[\r\n]+/g, ' ').trim()
  const tags = normalizeTags(options.tags)
  const timestamp = localIsoTimestamp(new Date())
  await mkdir(target.directory, { recursive: true })
  await writeFile(target.filePath, buildContent(title, timestamp, tags), 'utf8')

  const relative = path.relative(process.cwd(), target.filePath).split(path.sep).join('/')
  const slug = relative.replace(/^blog\//i, '').replace(/\.md$/i, '')
  console.log(`已创建 ${relative}`)
  console.log(`标题: ${title}`)
  console.log(`日期: ${timestamp}`)
  console.log(`标签: ${tags || '(未设置，分类将只取自目录)'}`)
  console.log(`地址: /blog/posts/${slug}`)

  const suggested = await collectSuggestedTags(blogRoot, entries)
  if (suggested.length) {
    console.log(`现有分类/标签: ${suggested.join(' ')}`)
  }
}

main().catch((error) => {
  fail(error instanceof Error ? error.message : String(error))
})
