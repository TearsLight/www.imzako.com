# Markdown 博客

主页 `/` 的站点导航下方展示最近更新的 **10 篇**博客；`/blog` 是独立列表入口，最多加载 **200 篇**，顶部搜索框可按标题、摘要或分类即时过滤。主页列表接近视口时加载，站内文章通过 Vue Router 进入详情，沿用全局主题和页面过渡。

## 新建和修改文章

默认内容目录是项目根目录的 `blog/`。启动 Spring Boot 时会自动创建目录并递归扫描其中的 UTF-8 `.md` 文件。默认作为公开文章展示；front matter 中设为 `draft: true` 的文件不会进入公开索引。

```text
blog/
  hello-markdown.md
  notes/
    first-post.md
    images/
      photo.png
```

例如新建 `blog/notes/first-post.md`：

```markdown
# 我的第一篇博客

这是一段 **Markdown** 正文。

## 一张图片

![照片](./images/photo.png)

[阅读另一篇文章](../hello-markdown.md)
```

保存后可访问 `/blog/posts/notes/first-post`。已有示例：`blog/hello-markdown.md`，对应 `/blog/posts/hello-markdown`。

- **标题**：front matter 的 `title`；没有时取第一个一级标题，再没有则使用文件名。建议以 `# 标题` 开头，随后空一行写正文。
- **摘要**：从正文自动提取，最多 180 字符。front matter 不参与摘要。
- **分类**：front matter 的 `tags` 与第一层子目录名合并，按出现顺序去重；两者都没有时归为“随笔”。
- **排序和时间**：front matter 的 `date`；没有或无法解析时回退到文件最后修改时间，最近更新的在前。
- **文章关联**：以相对文件路径（去掉 `.md`）作为 ID。修改正文保持原地址；重命名或移动文件改变地址，旧地址显示文章已删除或移动。
- **格式**：支持标题、列表、引用、表格、链接、图片和代码块。Markdown 由 `markdown-it` 转为 HTML，再经 DOMPurify 清理；内嵌原始 HTML 作为文字显示。
- **代码高亮**：代码围栏写明语言即可高亮，例如 ` ```java `、` ```typescript `。内置 JavaScript、TypeScript、Java、Python、Go、Rust、C/C++、C#、Kotlin、Swift、PHP、Ruby、SQL、Shell、CSS、JSON、YAML、Markdown 等常用语法；没有写语言或语言无法识别时自动检测。
- **图片**：相对路径以 `.md` 所在目录解析，支持 PNG、JPEG、GIF、WebP、AVIF。目录外文件不可访问，SVG 和其他非图片文件不通过图片接口提供。
- **体积**：单篇 Markdown 上限 1 MiB，超过会跳过并记录日志。

## Front matter

文章开头可选地写一段 front matter，用 `---` 包住：

```markdown
---
title: 名称
date: 2026-09-12T21:30:00+08:00
tags: 技术 笔记
draft: false
---

正文从这里开始。
```

| 字段    | 必填 | 行为                                                                                                         |
| ------- | ---- | ------------------------------------------------------------------------------------------------------------ |
| `title` | 否   | 作为文章标题；缺省时回退到第一个一级标题，再回退到文件名                                                     |
| `date`  | 否   | 排序与展示时间。接受带时区偏移的 ISO 时间、`2026-09-12 21:30:00`、`2026-09-12`；无法解析时回退到文件修改时间 |
| `tags`  | 否   | 标签。空格分隔，也接受逗号（含全角）与 `#` 前缀；值可以用引号包住                                            |
| `draft` | 否   | `true`、`yes` 或 `1` 表示草稿；列表和单篇文章接口均不返回该文件                                              |

解析细节：

- 只有位于**文件开头**的 `---` 块被当作 front matter；正文里面的 `---`（例如分隔线或表格）不受影响。
- 块内除 `title`、`date`、`tags` 和 `draft` 外的键（如 `author`）会被忽略，不影响文章。
- 整个块会从渲染内容与摘要中移除，因此不会出现在正文里。
- 未闭合的 `---` 块不视为 front matter，会按普通正文处理。
- 标题、日期、标签都缺省时，行为与不使用 front matter 完全一致。

**分类合并规则**：`categories` = tags 在前 + 第一层子目录在后，按出现顺序去重。

| 文件                   | front matter tags | 结果分类                      |
| ---------------------- | ----------------- | ----------------------------- |
| `blog/a.md`            | （无）            | `随笔`                        |
| `blog/notes/a.md`      | （无）            | `notes`                       |
| `blog/a.md`            | `技术 笔记`       | `技术`、`笔记`                |
| `blog/notes/a.md`      | `技术 笔记`       | `技术`、`笔记`、`notes`       |
| `blog/notes/deep/a.md` | `技术`            | `技术`、`notes`（只看第一层） |

API 同时返回列表 `categories` 与拼接后的 `category`（用 `·` 连接）供直接展示。

## 通过 JAR 新建文章

```text
imzako new "文章名称"
imzako new "文章名称" --tags "技术 笔记" --publish
imzako new "周记" --dir notes --tags "随笔"
```

| 选项                 | 说明                                               |
| -------------------- | -------------------------------------------------- |
| 第一个位置参数       | 文章标题，同时用于生成文件名（必填）               |
| `-t, --tags`         | 标签，空格分隔，写入 front matter 并计入分类       |
| `-d, --dir`          | 放入 `blog/` 下的子目录                            |
| `--publish`          | 立即公开；默认创建 `draft: true` 的草稿            |
| `--draft true/false` | 显式指定草稿状态                                   |

命令在 8090 RCON 认证后执行，由 JAR 直接写入带 `title`、`date`、`tags`、`draft` 的 Markdown 文件，运行时不依赖 Node/npm。文件名规则：含拉丁字母的标题转为小写连字符形式（`Hello World` → `hello-world.md`），纯中文标题保留原名；同名时自动追加 `-2`、`-3`。可用 `BLOG_DIRECTORY` 环境变量覆盖目标目录。等级 3 用户还可以在 `/blog` 的管理面板完成新增、编辑和删除；封禁期间仍可读取管理列表，但写操作返回 403。RCON 连接与安全配置见 [RCON 管理文档](rcon.md)。

## 实时更新与重连

后端通过文件监听检测新增、修改、重命名和删除，合并约 300ms 内的连续保存事件，再更新内存索引并推送 SSE `changed` 通知。前端收到通知重新请求列表或当前文章，将最新 Markdown 渲染为 HTML，不需要手动刷新页面或重新构建项目。

每次 SSE 建立连接（包括断线重连）都会发送 `sync`，前端重新获取当前数据，补上断线期间遗漏的修改。服务端发送 15 秒心跳，浏览器按服务端提供的 2 秒重连间隔重试。页面重新可见、网络恢复时也会触发同步。文件系统监听事件遗漏时，后端每 10 秒复扫一次作为补偿。

请求超时为 8 秒，失败时提供重试入口；后台同步失败保留已有文章。删除正在阅读的源文件后，正文会移除并提示返回博客。离开页面会取消请求并关闭 SSE 连接。

## 配置与运行

根目录 `.env` 已增加：

```dotenv
BLOG_DIRECTORY=blog
VITE_BLOG_API_URL=/api/blog/posts
VITE_BLOG_POST_URL=/api/blog/post
VITE_BLOG_EVENTS_URL=/api/blog/events
```

`BLOG_DIRECTORY` 相对于后端进程的工作目录，也可使用绝对路径，例如 `E:/Website/www.imzako.com/blog`。Windows 在 `.env` 中建议使用正斜杠，避免 Java properties 将反斜杠解释为转义。修改目录配置需要重启后端；修改目录里的 Markdown 无需重启。

在项目根目录运行：

```powershell
.\mvnw.cmd spring-boot:run
```

然后打开 `http://localhost:8080/blog`。前端开发可另开终端运行 `npm run dev`，访问 `http://localhost:5173/blog`，Vite 会将 `/api` 转发给后端。仅启动 Vite 不会扫描 Markdown，后端也需要运行。

部署 JAR 时，将 `blog/` 作为外部内容目录一并部署，或设置 `BLOG_DIRECTORY` 指向持久化目录；Markdown 不打包进 JAR，运行时保存文件即可发布。前端 `VITE_` 变量修改后需要重新构建。若使用 Nginx 代理 `/api/blog/events`，关闭该路径的缓冲（`proxy_buffering off`），并确保连接读取超时大于 15 秒心跳间隔。

## 接口

| 地址                                               | 内容                                                               |
| -------------------------------------------------- | ------------------------------------------------------------------ |
| `GET /api/blog/posts?limit=10&q=Java`              | `{ posts, revision }`；`limit` 最大 200，`q` 匹配标题、摘要和分类 |
| `GET /api/blog/post?slug=notes/first-post`         | `{ post, markdown, updatedAt }`，不存在返回 404                    |
| `GET /api/blog/events`                             | SSE：`sync`、`changed`、`heartbeat`，包含当前 `revision`           |
| `GET /api/blog/assets?path=notes/images/photo.png` | 目录内图片                                                         |
| `GET /blog/posts/notes/first-post`                 | Vue 文章详情入口，支持直接打开和刷新                               |

等级 3 博客管理接口：

| 方法与地址                                         | 请求/响应与权限                                                     |
| -------------------------------------------------- | ------------------------------------------------------------------- |
| `GET /api/blog/manage/posts`                       | 返回含草稿的管理列表；需要有效会话和等级 3                          |
| `GET /api/blog/manage/post?slug=notes/first-post`  | 返回可编辑的 front matter 与 Markdown；需要等级 3                   |
| `POST /api/blog/manage/posts`                      | 请求体 `{ title, tags, draft, directory }`；封禁期间返回 403        |
| `PUT /api/blog/manage/post`                        | 请求体 `{ slug, title, tags, draft, markdown }`；封禁期间返回 403   |
| `DELETE /api/blog/manage/post?slug=...`            | 删除文章；成功返回 204，封禁期间返回 403                            |

`post` 字段为 `id`、`title`、`excerpt`、`url`、`publishedAt`、`category`。为兼容已有列表结构，`publishedAt` 在文件博客中代表文件修改时间。JSON 接口使用 `Cache-Control: no-store`，避免文章更新后读到旧缓存。

## 验证更新

1. 打开 `/blog/posts/hello-markdown`，保留页面。
2. 修改 `blog/hello-markdown.md` 的最后一句并保存，正文应自动显示新内容。
3. 回到 `/` 下方博客区域，新建文章后应进入列表；超过 10 篇时主页仍只显示最近更新的 10 篇。
4. 打开 `/blog`，输入标题、摘要或分类关键词，确认结果即时过滤且无结果状态正确。
5. 使用等级 3 账号新建、修改和删除一篇草稿；封禁该账号后确认写操作返回 403。
6. 重命名、移动或删除 `.md`，检查列表和旧文章地址。
7. 断开网络后修改文件，再恢复网络，页面应重新同步。

自动验证命令：

```powershell
npm run test:unit -- --run
.\mvnw.cmd verify
```

前端测试覆盖 Markdown 转换、链接与图片关联、内容清理、同步后更新 DOM、删除和断线同步。Java 测试在独立临时目录真实写入、替换、重命名和删除文件，并通过实际 HTTP/SSE 验证变化与重连；不会修改你的文章。
