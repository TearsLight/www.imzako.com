# Markdown 博客

主页 `/` 的站点导航下方展示博客，向下滚动即可浏览；`/blog` 是独立列表入口。两个位置均最多展示最近更新的 **10 篇**，不会无限追加。主页列表接近视口时加载，站内文章通过 Vue Router 进入详情，沿用全局主题和页面过渡。

## 新建和修改文章

默认内容目录是项目根目录的 `blog/`。启动 Spring Boot 时会自动创建目录并递归扫描其中的 UTF-8 `.md` 文件。目录中的 Markdown 都作为公开文章展示。

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

- **标题**：第一个一级标题；没有一级标题时使用文件名。建议以 `# 标题` 开头，随后空一行写正文。
- **摘要**：从正文自动提取，最多 180 字符。
- **分类**：第一层子目录名；根目录文章归为“随笔”。
- **排序和时间**：使用文件最后修改时间，最近更新的在前。目前不解析 YAML front matter，无需添加配置头。
- **文章关联**：以相对文件路径（去掉 `.md`）作为 ID。修改正文保持原地址；重命名或移动文件改变地址，旧地址显示文章已删除或移动。
- **格式**：支持标题、列表、引用、表格、链接、图片和代码块。Markdown 由 `markdown-it` 转为 HTML，再经 DOMPurify 清理；内嵌原始 HTML 作为文字显示。
- **代码高亮**：代码围栏写明语言即可高亮，例如 ` ```java `、` ```typescript `。内置 JavaScript、TypeScript、Java、Python、Go、Rust、C/C++、C#、Kotlin、Swift、PHP、Ruby、SQL、Shell、CSS、JSON、YAML、Markdown 等常用语法；没有写语言或语言无法识别时自动检测。
- **图片**：相对路径以 `.md` 所在目录解析，支持 PNG、JPEG、GIF、WebP、AVIF。目录外文件不可访问，SVG 和其他非图片文件不通过图片接口提供。
- **体积**：单篇 Markdown 上限 1 MiB，超过会跳过并记录日志。

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

| 地址                                               | 内容                                                     |
| -------------------------------------------------- | -------------------------------------------------------- |
| `GET /api/blog/posts?limit=10`                     | `{ posts, revision }`，后端同样最多返回 10 篇            |
| `GET /api/blog/post?slug=notes/first-post`         | `{ post, markdown, updatedAt }`，不存在返回 404          |
| `GET /api/blog/events`                             | SSE：`sync`、`changed`、`heartbeat`，包含当前 `revision` |
| `GET /api/blog/assets?path=notes/images/photo.png` | 目录内图片                                               |
| `GET /blog/posts/notes/first-post`                 | Vue 文章详情入口，支持直接打开和刷新                     |

`post` 字段为 `id`、`title`、`excerpt`、`url`、`publishedAt`、`category`。为兼容已有列表结构，`publishedAt` 在文件博客中代表文件修改时间。JSON 接口使用 `Cache-Control: no-store`，避免文章更新后读到旧缓存。

## 验证更新

1. 打开 `/blog/posts/hello-markdown`，保留页面。
2. 修改 `blog/hello-markdown.md` 的最后一句并保存，正文应自动显示新内容。
3. 回到 `/` 下方博客区域，新建文章后应进入列表；超过 10 篇仍只显示最近更新的 10 篇。
4. 重命名、移动或删除 `.md`，检查列表和旧文章地址。
5. 断开网络后修改文件，再恢复网络，页面应重新同步。

自动验证命令：

```powershell
npm run test:unit -- --run
.\mvnw.cmd verify
```

前端测试覆盖 Markdown 转换、链接与图片关联、内容清理、同步后更新 DOM、删除和断线同步。Java 测试在独立临时目录真实写入、替换、重命名和删除文件，并通过实际 HTTP/SSE 验证变化与重连；不会修改你的文章。
