# www.imzako.com

`www.imzako.com` 是一个个人服务门户与文件驱动博客。前端使用 Vue 3、TypeScript 和 Vite，后端使用 Java 21、Spring Boot 4.1.1 与 MongoDB。

主页 `/` 展示服务导航，继续向下滚动即可进入博客区域并浏览最近更新的 10 篇文章。顶部导航、登录与注册入口、白天/夜间模式和一言页脚在所有页面共用。登录后顶部显示「欢迎{用户名}」菜单，可进入 `/account` 管理头像、用户名、邮箱和密码。

## 当前功能

### 主页与全局界面

- `/` 对应 `HomePage`，包含题头、个人服务入口和预留站点卡片。
- Git 服务默认指向 `https://git.imzako.com`。
- 博客默认指向 `http://localhost:8080/blog`，生产地址预留为 `https://www.imzako.com/blog`。
- 邮箱默认指向 `https://mail.imzako.com`。
- 可通过 `VITE_EXTRA_SITES_JSON` 增加其他站点，无需修改页面模板。
- 顶部导航在所有路由共用，包含首页、Git、博客、登录状态和主题开关。
- 未登录时显示“用户登录”入口；登录后替换为带头像的“欢迎{用户名}”按钮，点击展开菜单，可进入 `/account` 或退出登录。
- 白天/夜间模式采用黑白主视觉，首次访问跟随系统设置，手动选择保存在 `localStorage`。
- 页面使用平滑滚动、进出场过渡和分层动画；系统启用“减少动态效果”时自动关闭非必要动画。
- 布局适配桌面、平板和手机，并提供跳转到主要内容、键盘焦点和语义化状态提示。

### 一言页脚

页脚通过一言接口读取句子：

```text
https://v1.hitokoto.cn/?encode=json&max_length=60
```

结果在浏览器缓存 1 小时，接口失败时保留仍可用的缓存内容。句子会链接到：

```text
https://hitokoto.cn?uuid=[uuid]
```

接口地址可通过 `VITE_HITOKOTO_API_URL` 修改，也可以指向自行部署的一言服务。

### Markdown 博客

- 文章来源为项目根目录的 `blog/`，支持递归子目录和中文文件名。
- Spring Boot 启动时创建并扫描博客目录，新增、修改、覆盖保存、移动、重命名和删除 `.md` 文件都会自动更新索引。
- 文件事件合并约 300ms 后通过 SSE 推送；连接中断后浏览器自动重连并执行完整同步。
- 服务端每 15 秒发送心跳，并每 10 秒补偿扫描一次，避免遗漏文件系统事件。
- `/` 下方显示最近更新的 10 篇；独立 `/blog` 最多加载 200 篇，并可按标题、摘要或分类即时搜索。
- `/blog/posts/{slug}` 渲染文章详情，支持子目录，例如 `blog/notes/first.md` 对应 `/blog/posts/notes/first`。
- 支持标题、列表、引用、表格、链接、代码块和相对路径图片。
- 本地图片支持 PNG、JPEG、GIF、WebP 和 AVIF，并限制在博客目录内读取。
- 单篇 Markdown 最大 1 MiB，超过限制的文件不会进入文章索引。
- 原始 HTML 默认关闭，渲染结果经 DOMPurify 清理后再插入页面。

保存 Markdown 后，无需重新构建或刷新浏览器，已打开的列表和正文会自动同步。详细说明见 [Markdown 博客文档](docs/frontend-blog.md)。

#### Front matter

文章开头的 front matter 决定标题、日期与分类，整块内容不会出现在正文或摘要里：

```markdown
---
title: 名称
date: 2026-09-12T21:30:00+08:00
tags: 技术 笔记
draft: false
---
```

| 字段    | 说明                                                                                                         |
| ------- | ------------------------------------------------------------------------------------------------------------ |
| `title` | 文章标题；缺省时回退到第一个一级标题，再回退到文件名                                                         |
| `date`  | 排序与展示用时间；接受带偏移的 ISO 时间、`2026-09-12 21:30:00` 或 `2026-09-12`，无法解析时回退到文件修改时间 |
| `tags`  | 空格分隔的标签，也可用逗号或 `#` 前缀；与所在子目录一起构成分类                                              |
| `draft` | 设为 `true`、`yes` 或 `1` 时不进入公开列表和正文接口，适合占位稿和未完成文章                                 |

**分类规则**：`categories` = front matter 的 tags + 文章所在的第一层子目录，按出现顺序去重；两者都没有时归为「随笔」。例如 `blog/notes/a.md` 且 `tags: 技术 笔记`，分类为 `技术`、`笔记`、`notes`。API 同时返回数组 `categories` 与拼接后的 `category` 字符串供展示。

front matter 是可选的，没有它时行为与之前一致。

#### 通过 JAR 新建文章

JAR 在 8090 提供带令牌认证的行协议 RCON。连接并执行 `auth <RCON_TOKEN>` 后，可直接运行：

```text
imzako new "文章名称"
imzako new "并发笔记" --tags "技术 Java" --publish
imzako new "周记" --dir notes --tags "随笔"
```

| 选项                 | 说明                                               |
| -------------------- | -------------------------------------------------- |
| 第一个位置参数       | 文章标题，同时用于生成文件名（必填）               |
| `-t, --tags`         | 标签，空格分隔，写入 front matter 并计入分类       |
| `-d, --dir`          | 放入 `blog/` 下的子目录                            |
| `--publish`          | 立即进入公开列表；缺省时生成 `draft: true` 的草稿 |
| `--draft true/false` | 显式指定草稿状态                                   |

命令由 Java 直接写入 `BLOG_DIRECTORY`，运行时不需要 Node/npm。生成文件包含 `title`、`date`（`yyyy-MM-dd HH-mm`）、`tags` 和 `draft`；同名时自动追加 `-2`、`-3`，不会覆盖已有文章。等级 3 用户登录网页后，也会在 `/blog` 看到同一套新增、修改和删除界面。

RCON 还提供 `op UID 1|2|3`、`ban UID 小时数 "理由"`、`pardon UID` 和 `user UID`。等级分别为普通用户、管理员、服主；封禁记录在到期前会拒绝受保护的业务写操作。协议、安全边界和完整命令见 [RCON 管理文档](docs/rcon.md)。

#### 代码语法高亮

代码块使用 Highlight.js。为代码围栏指定语言即可显示高亮：

````markdown
```typescript
const message: string = 'Hello, Markdown!'
console.log(message)
```
````

当前按需加载常用语言集合，包括 JavaScript、TypeScript、Java、Python、Go、Rust、C/C++、C#、Kotlin、Swift、PHP、Ruby、SQL、Shell、CSS、JSON、YAML 和 Markdown。没有指定语言或语言无法识别时会自动检测。高亮配色同时适配白天和夜间主题。

### 注册邮箱验证（当前关闭）

注册邮箱验证的完整链路已经实现，但**默认关闭**，因此注册后会直接登录，不会发送验证码。通过 `.env` 打开：

```dotenv
EMAIL_VERIFICATION_ENABLED=true
```

打开后行为如下：

- 注册先创建账号并标记为未验证，然后向该邮箱发送 6 位验证码，此时**不建立登录会话**；如果邮件发送失败，则回滚新账号和验证记录，不会留下无法重新注册的孤立账号。
- 未验证邮箱的账号**无法登录**：提交正确的密码会收到 `428 EMAIL_NOT_VERIFIED` 与打码后的邮箱地址，前端据此切换到验证码输入界面。
- 输入正确验证码后邮箱置为已验证并建立会话；验证码一次性使用，5 次输入失败即作废。
- `POST /api/auth/register/resend` 可重发，默认 60 秒冷却、每小时最多 5 封；验证码到期时由 MongoDB TTL 索引自动删除。
- 注册、重发邮件和生成图形验证码还有按来源地址的固定时间窗限流，超限返回 `429 RATE_LIMITED`。
- 存量账号（无 `emailVerified` 字段）在启动时会被标记为已验证，避免升级后失联。
- 非 `production` 环境下，验证码会同时写入服务端日志，便于本地联调。

生产环境通过 BillionMail 发送 API 投递邮件（`MAIL_TRANSPORT=billionmail`，发件人 `noreply@imzako.com`）。`.env` 只配置 BillionMail 的 `BILLIONMAIL_API_URL`、`BILLIONMAIL_API_KEY`；SMTP 仅作可选本地回退，需要时再自行配置 `SMTP_*`。

### 登录图形验证码（当前关闭）

连续登录失败后强制图形验证码的功能也已实现，同样**默认关闭**：

```dotenv
CAPTCHA_ENABLED=true
```

打开后：同一邮箱或同一来源地址在窗口期内累计失败达到阈值（默认 3 次，可用 `LOGIN_CAPTCHA_THRESHOLD`、`LOGIN_FAILURE_WINDOW_SECONDS` 调整）时，后续登录先返回 `428 CAPTCHA_REQUIRED`；前端调用 `GET /api/auth/captcha` 取回验证码图片，并把响应头 `X-Captcha-Id` 与用户输入一并提交。验证码由服务端用 JDK 图像 API 生成，4 位字符，只存于服务端内存，5 分钟有效且一次性使用，答错返回 `400 INVALID_CAPTCHA`。登录成功会清零计数。失败计数和验证码记录都有最大数量限制，攻击者无法通过持续更换标识无限占用内存。

### 用户、登录与管理员

- `/login` 提供登录前端，支持**邮箱或 UID** 登录。
- `/register` 开放普通用户注册，包含邮箱、用户名、密码确认字段的前端校验。
- `POST /api/auth/register` 创建普通用户；邮箱验证关闭时直接建立会话，开启时返回需要验证。
- 首位注册用户的 UID 为 `1000001`，后续注册通过 MongoDB 原子序列递增。
- **UID 与邮箱是唯一标识**；用户名只是展示用的标签，**允许重复，不能用于登录**。
- 邮箱使用规范化检索键避免大小写重复；`usernameKey` 保留但不再有唯一索引。
- 密码使用 Spring Security `DelegatingPasswordEncoder`，当前写入带随机盐的 bcrypt 单向哈希。
- 头像以压缩后的 JPEG data URI 保存在用户文档中，服务端限制在 300000 字符以内。
- API 和页面不会返回密码哈希、MongoDB `_id` 或数据库凭据。
- 登录通过 HttpOnly Session Cookie 保持，名称、路径、Secure、SameSite 和有效期由 `.env` 配置。
- `admin_users` 保存 UID 的权限等级：1 普通用户、2 管理员、3 服主；旧版没有 `level` 字段的记录按等级 2 兼容。
- 权限由认证后的 RCON `op UID 等级` 修改。等级 3 会获得 `OWNER` 角色和博客管理接口，权限变更无需重新登录即可在下次请求生效。
- `user_bans` 保存限时封禁；封禁用户仍可读取公开内容，博客管理写接口会拒绝其操作，并由 TTL 索引在到期后清理记录。后续聊天室、留言板和评论区接入时也必须复用同一写权限检查。

### 用户管理

登录后顶部显示「欢迎{用户名}」，点击展开菜单可进入 `/account`，或直接退出登录。

| 管理项     | 界面位置              | 接口                     | 要求                                           |
| ---------- | --------------------- | ------------------------ | ---------------------------------------------- |
| 修改头像   | `/account` 头像卡片   | `PUT /api/auth/avatar`   | 图片，客户端裁剪为 256×256                     |
| 修改用户名 | `/account` 用户名卡片 | `PUT /api/auth/username` | 3–32 字符，允许与他人重复                      |
| 改绑邮箱   | `/account` 邮箱卡片   | `PUT /api/auth/email`    | 需当前密码；验证开启时需输入新邮箱收到的验证码 |
| 修改密码   | `/account` 密码卡片   | `PUT /api/auth/password` | 需当前密码，成功后该账号的全部会话失效         |

头像在前端读取文件后用 `canvas` 居中裁剪并压缩为 JPEG data URI，超过 5 MB 的源文件会被拒绝。开启邮箱验证时，改绑会在新地址验证成功前保留原邮箱。密码修改成功后服务端递增账号会话版本，所有已登录设备的会话都会在下次请求时失效。

MongoDB 使用业务数据库 `imzako`，启动时创建并检查：

| 集合                  | 用途                                                |
| --------------------- | --------------------------------------------------- |
| `users`               | 网站用户、资料与密码哈希                            |
| `admin_users`         | UID 权限等级 1/2/3（保留旧集合名以兼容升级）        |
| `user_bans`           | 用户临时封禁、到期时间与原因                        |
| `sequences`           | UID 原子递增序列，初始值为 `1000000`                |
| `email_verifications` | 待验证的邮箱验证码（只有哈希，到期由 TTL 索引清理） |

启动时还会删除旧版本遗留的 `username_unique` 索引，并把没有 `emailVerified` 字段的存量账号标记为已验证。

`authSource=admin` 只表示 MongoDB 凭据在系统 `admin` 数据库中验证；网站用户不会存入 MongoDB 系统数据库。字段、注册请求和管理员授权方法见 [账号与管理员文档](docs/frontend-auth.md)。

## 技术栈

| 层级     | 技术                                                                |
| -------- | ------------------------------------------------------------------- |
| 前端     | Vue 3、TypeScript、Vue Router、Vite                                 |
| Markdown | markdown-it、Highlight.js、DOMPurify                                |
| 后端     | Java 21、Spring Boot 4.1.1、Spring Web MVC                          |
| 数据     | Spring Data MongoDB、MongoDB                                        |
| 密码     | Spring Security Crypto、bcrypt                                      |
| 测试     | Vitest、Vue Test Utils、JUnit 5、Spring Boot Test、AssertJ、Mockito |
| 构建     | npm、Maven Wrapper、Spring Boot Maven Plugin                        |

## 环境要求

- JDK 21。当前开发机可使用 `E:\\Environment\\Java\\jdk-21.0.11`。
- Node.js `^22.18.0 || >=24.12.0`。
- 项目自带 Maven Wrapper 3.9.16，无需安装全局 Maven。
- 可访问配置中的 MongoDB 服务。

当前开发机的系统默认 `java` 可能不是 JDK 21。运行 JAR 前先执行 `java -version`；若版本不正确，请使用 JDK 21 的完整路径或调整 `PATH`。

首次安装前端依赖：

```powershell
npm ci
```

项目通过阿里云 Maven Central 镜像下载 Java 依赖，并保留 TLS 证书验证。

## 本地开发

在项目根目录打开两个 PowerShell 终端。

启动后端：

```powershell
.\mvnw.cmd spring-boot:run
```

启动 Vite：

```powershell
npm run dev
```

开发地址：

| 地址                             | 用途                           |
| -------------------------------- | ------------------------------ |
| `http://localhost:5173/`         | Vite 主页与前端热更新          |
| `http://localhost:5173/blog`     | Vite 博客列表                  |
| `http://localhost:5173/login`    | Vite 登录页面                  |
| `http://localhost:5173/register` | Vite 注册页面                  |
| `http://localhost:5173/account`  | Vite 用户管理页面              |
| `http://localhost:8080/`         | Spring Boot 打包后的前端与 API |

新建一篇文章（连接本机 8090 RCON，认证后执行）：

```text
imzako new "文章名称" --tags "技术 笔记" --publish
```

Vite 将 `/api` 转发到 `BACKEND_URL`，默认是 `http://127.0.0.1:8080`。仅启动 Vite 时不会扫描 Markdown、连接 MongoDB 或提供认证接口，因此完整开发环境需要同时启动后端。

macOS 和 Linux 使用 `./mvnw` 替代 `.\mvnw.cmd`，必要时先执行 `chmod +x mvnw`。

### IDEA

1. 打开项目根目录或 `pom.xml`，必要时选择 **Add as Maven Project**。
2. 在 **Project Structure → Project SDK** 中选择 JDK 21。
3. Maven Runner 的 JRE 选择 **Project JDK**。
4. 使用 `.run` 中的共享运行配置启动或验证项目。

| 配置       | 用途                                  |
| ---------- | ------------------------------------- |
| Full Stack | 同时启动 Backend 与 Frontend          |
| Backend    | 构建前端并启动 Spring Boot，端口 8080 |
| Frontend   | 启动 Vite，端口 5173，并代理 `/api`   |
| Verify     | 构建前端、运行 Java 测试并打包 JAR    |

修改 Vue 文件后由 Vite 热更新。修改 Java 文件后执行 **Build Project**，DevTools 检测到编译结果后会重启后端。直接访问 8080 时看到的是后端最近一次构建包含的前端产物。

## 页面路由

| 地址                              | 结果                                      |
| --------------------------------- | ----------------------------------------- |
| `GET /`                           | HomePage 服务导航，下滑进入博客           |
| `GET /blog`、`GET /blog/`         | 最多 200 篇文章，并提供标题、摘要和分类搜索 |
| `GET /blog/posts/{slug}`          | Markdown 文章详情，支持嵌套路径与直接刷新 |
| `GET /login`、`GET /login/`       | 用户登录页面                              |
| `GET /register`、`GET /register/` | 用户注册页面                              |
| `GET /account`、`GET /account/`   | 用户管理页面（头像、用户名、邮箱、密码）  |
| `GET /about`、`GET /about/`       | 兼容旧地址，前端重定向至 `/`              |

只有已知页面路由会转发至 Vue。未知页面、API 和静态资源返回 404，避免把错误请求误返回为 HTML。

## 后端 API

### 通用与博客

| 接口                                               | 说明                                                       |
| -------------------------------------------------- | ---------------------------------------------------------- |
| `GET /api/health`                                  | 返回应用存活状态                                           |
| `GET /api/blog/posts?limit=10&q=Java`              | 返回文章列表和 revision；`q` 可按标题、摘要和分类筛选      |
| `GET /api/blog/post?slug=notes/first`              | 返回文章元数据、Markdown 与更新时间                        |
| `GET /api/blog/events`                             | SSE `sync`、`changed`、`heartbeat` 事件                    |
| `GET /api/blog/assets?path=notes/images/photo.png` | 读取博客目录内允许的图片                                   |
| `GET /api/blog/manage/posts`                       | 等级 3 读取含草稿的管理列表                                |
| `GET /api/blog/manage/post?slug=notes/first`       | 等级 3 读取待编辑的 front matter 与 Markdown               |
| `POST /api/blog/manage/posts`                      | 等级 3 新建文章；封禁期间返回 403                          |
| `PUT /api/blog/manage/post`                        | 等级 3 修改文章；封禁期间返回 403                          |
| `DELETE /api/blog/manage/post?slug=notes/first`    | 等级 3 删除文章；封禁期间返回 403                          |

### 账号

| 接口                             | 说明                                                          |
| -------------------------------- | ------------------------------------------------------------- |
| `POST /api/auth/register`        | 注册普通用户；邮箱验证关闭时返回 201 并建立会话               |
| `POST /api/auth/register/verify` | 提交邮箱验证码；成功返回用户并建立会话（验证开启时使用）      |
| `POST /api/auth/register/resend` | 重发邮箱验证码；返回 204，冷却期内返回 400                    |
| `GET /api/auth/captcha`          | 返回 PNG 图形验证码，答案在 `X-Captcha-Id` 对应的服务端记录中 |
| `POST /api/auth/login`           | 以**邮箱或 UID** 登录；需要时返回 428 要求验证码或邮箱验证    |
| `GET /api/auth/me`               | 获取当前会话用户；未登录返回 401                              |
| `PUT /api/auth/username`         | 修改用户名；允许与他人重复，因此不会返回 409                  |
| `PUT /api/auth/avatar`           | 设置或清除头像（`avatar: null` 表示清除）                     |
| `PUT /api/auth/email`            | 改绑邮箱；需当前密码，新邮箱已存在时返回 409                  |
| `POST /api/auth/email/verify`    | 提交新邮箱收到的验证码，成功后完成改绑                        |
| `PUT /api/auth/password`         | 修改密码；需当前密码，成功后使该账号的全部会话失效并返回 204  |
| `POST /api/auth/logout`          | 注销并使当前会话失效，返回 204                                |

登录成功返回（邮箱验证关闭时的常规响应）：

```json
{
  "user": {
    "id": 1000001,
    "username": "member",
    "email": "member@example.com",
    "roles": ["USER"],
    "permissionLevel": 1,
    "bannedUntil": null,
    "banReason": null
  },
  "verificationRequired": false,
  "maskedEmail": null
}
```

需要邮箱验证时 `user` 为 `null`、`verificationRequired` 为 `true`，`maskedEmail` 给出收信地址的打码形式。

注册示例：

```json
{
  "email": "member@example.com",
  "username": "member",
  "password": "a-long-private-password",
  "avatar": "data:image/jpeg;base64,..."
}
```

`avatar` 可省略。`gender` 与 `birthday` 字段已从注册接口移除。

改绑邮箱示例：

```json
{ "currentPassword": "a-long-private-password", "email": "new@example.com" }
```

## `.env` 配置

根目录 `.env` 集中保存项目运行配置，并已被 Git 忽略；仓库提供已脱敏的 `.env.example` 模板，首次部署复制为 `.env` 并填入真实密钥。Spring Boot 通过 `spring.config.import` 读取该文件；Vite 读取 `BACKEND_URL`，并只向浏览器暴露 `VITE_` 前缀的变量。

不要把 `.env`、数据库密码、Cookie 密钥、Token 密钥或 SMTP 密码提交到版本库。

| 分组          | 变量                                                                                                                                                                                                                                                                                                                                                                                                                                      | 当前用途                                                      |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| 应用与域名    | `APP_NAME`、`APP_ENV`、`PUBLIC_DOMAIN`、`PUBLIC_URL`、`GIT_DOMAIN`、`BLOG_PUBLIC_URL`                                                                                                                                                                                                                                                                                                                                                     | 服务端与部署环境信息                                          |
| 本地端口      | `BACKEND_URL`、`FRONTEND_DEV_URL`、`SERVER_PORT`、`TRUST_PROXY`                                                                                                                                                                                                                                                                                                                                                                           | 后端端口与 Vite API 代理                                      |
| RCON           | `RCON_ENABLED`、`RCON_HOST`、`RCON_PORT`、`RCON_TOKEN`                                                                                                                                                                                                                                                                                                                                                                                     | 8090 管理端口、监听地址与认证令牌                             |
| Cookie 与会话 | `COOKIE_NAME`、`COOKIE_DOMAIN`、`COOKIE_PATH`、`COOKIE_HTTP_ONLY`、`COOKIE_SECURE`、`COOKIE_SAME_SITE`、`COOKIE_SECRET`、`SESSION_TTL_SECONDS`                                                                                                                                                                                                                                                                                            | 登录会话；当前直接使用除 Domain 与 Secret 外的 Cookie 配置    |
| Token         | `TOKEN_ISSUER`、`ACCESS_TOKEN_TTL_SECONDS`、`REFRESH_TOKEN_TTL_SECONDS`、`EMAIL_VERIFICATION_TOKEN_TTL_SECONDS`、`PASSWORD_RESET_TOKEN_TTL_SECONDS`、`TOKEN_SECRET`                                                                                                                                                                                                                                                                       | 为后续 Token、邮箱验证与密码重置预留                          |
| MongoDB       | `MONGODB_URI`、`MONGODB_DATABASE`、`MONGODB_USERNAME`、`MONGODB_PASSWORD`、`MONGODB_AUTH_SOURCE`、`MONGODB_CONNECT_TIMEOUT_MS`、`MONGODB_SOCKET_TIMEOUT_MS`                                                                                                                                                                                                                                                                               | 用户、管理员与 UID 序列；后端当前使用 `MONGODB_URI`           |
| 跨域          | `ALLOWED_ORIGINS`、`ALLOWED_METHODS`、`ALLOWED_HEADERS`、`ALLOW_CREDENTIALS`                                                                                                                                                                                                                                                                                                                                                              | 当前启用允许来源与携带凭据；方法和请求头采用后端安全白名单    |
| 安全预留      | `CSRF_ENABLED`、`PASSWORD_MIN_LENGTH`、`BCRYPT_COST`、`LOGIN_MAX_ATTEMPTS`、`LOGIN_LOCK_SECONDS`、`RATE_LIMIT_ENABLED`、`RATE_LIMIT_WINDOW_SECONDS`、`RATE_LIMIT_MAX_REQUESTS`                                                                                                                                                                                                                                                            | 后续 CSRF、密码策略、登录锁定和限流扩展；尚未全部接入运行逻辑 |
| SMTP          | `SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASS`、`SMTP_TLS`、`SMTP_AUTH`、`SMTP_CONNECTION_TIMEOUT_MS`、`SMTP_TIMEOUT_MS`、`SENDER`、`SENDER_NAME`                                                                                                                                                                                                                                                                                     | 可选本地回退（需自行配置），映射到 `spring.mail.*` 与 `app.mail.*` |
| BillionMail   | `MAIL_TRANSPORT`、`BILLIONMAIL_API_URL`、`BILLIONMAIL_API_KEY`、`BILLIONMAIL_TIMEOUT_MS` | 生产邮件发送 API，发件人 `noreply@imzako.com` |        |
| 账号门禁      | `EMAIL_VERIFICATION_ENABLED`、`CAPTCHA_ENABLED`、`EMAIL_VERIFICATION_TTL_SECONDS`、`EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS`、`EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR`、`LOGIN_CAPTCHA_THRESHOLD`、`LOGIN_FAILURE_WINDOW_SECONDS`、`LOGIN_FAILURE_MAX_TRACKED_KEYS`、`REGISTRATION_MAX_REQUESTS_PER_HOUR`、`VERIFICATION_RESEND_MAX_REQUESTS_PER_HOUR`、`CAPTCHA_MAX_REQUESTS_PER_MINUTE`、`PUBLIC_RATE_LIMIT_MAX_TRACKED_KEYS` | 邮箱验证、图形验证码和公开接口限流                            |
| 前端站点      | `VITE_SITE_NAME`、`VITE_PUBLIC_DOMAIN`、`VITE_GIT_URL`、`VITE_BLOG_URL`、`VITE_MAIL_URL`、`VITE_EXTRA_SITES_JSON`                                                                                                                                                                                                                                                                                                                                          | 品牌、域名和服务导航                                          |
| 前端接口      | `VITE_LOGIN_ENDPOINT`、`VITE_REGISTER_ENDPOINT`、`VITE_HITOKOTO_API_URL`                                                                                                                                                                                                                                                                                                                                                                  | 登录、注册与一言接口地址                                      |
| 博客          | `BLOG_DIRECTORY`、`VITE_BLOG_API_URL`、`VITE_BLOG_POST_URL`、`VITE_BLOG_EVENTS_URL`                                                                                                                                                                                                                                                                                                                                                       | Markdown 目录、列表、正文与实时通知                           |

额外站点配置示例：

```dotenv
VITE_EXTRA_SITES_JSON=[{"name":"Status","label":"SERVICE STATUS","description":"服务状态","href":"https://status.example.com"}]
```

修改 `.env` 后需要重启使用该配置的开发进程。修改 `VITE_` 变量后，生产版本还需要重新构建前端。

## 构建与测试

运行前端测试：

```powershell
npm run test:unit -- --run
```

执行完整 Java 构建、测试和打包：

```powershell
.\mvnw.cmd clean verify
```

Maven 在 `generate-resources` 阶段执行 `npm run build`，其中包含 TypeScript 类型检查，然后将 `dist` 复制到 JAR 的静态资源目录；`test` 阶段会执行 Vitest，因此 `verify` 会同时验证前后端。

最近一次验证结果：

- **74 项前端测试通过**，覆盖登录、注册、账号管理、博客搜索与等级 3 管理界面。
- **75 项 Java 测试通过**，另有 1 项真实 SMTP 探测默认跳过；覆盖 front matter、博客文件管理、RCON、权限封禁、验证码、限流和会话版本。
- Markdown 文件新增、修改、替换保存、重命名、删除和 SSE 重连均使用真实临时文件与 HTTP 连接测试。
- front matter 的标题、日期、标签、缺省回退与「不泄漏进正文/摘要」均有测试覆盖。
- 密码哈希、UID 分配、普通用户与管理员角色判定包含独立测试。
- 真实 SMTP 发信是显式开启的探测项，常规 `verify` 不会对外发送邮件；邮件发送和失败回滚在单元测试中通过可控的 `MailService` 验证。
- `npm audit` 未发现漏洞。

## 打包与部署

仓库使用两条长期分支：

- `dev/test`：日常开发与验证。
- `main`：生产发布分支。服务器工作区只检出并跟踪 `origin/main`。

发布时先在 `dev/test` 完成测试，再把可发布提交快进合并到 `main`。不要直接在服务器修改受 Git 跟踪的代码。

构建完成后运行：

```powershell
java -jar target\imzako-0.0.1-SNAPSHOT.jar
```

`mvn verify` 会先构建并测试 Vue 前端，再把静态产物嵌入 Spring Boot JAR。运行目录只需要该 JAR、同目录 `.env` 和 JDK 21，不需要 Node、Vite 或外部 Tomcat。首次新增博客时会自动创建 `BLOG_DIRECTORY`（默认 `blog/`）。生产环境可由 1Panel 的 Java 21 Docker 容器运行，具体配置见 [上线清单](docs/deployment.md)。

部署时需要：

1. 在应用工作目录保留外部 `blog/`，或将 `BLOG_DIRECTORY` 设置为持久化绝对路径。
2. 提供生产环境 `.env` 或等价环境变量。
3. 将 `COOKIE_SECURE` 设置为 `true`，并使用 HTTPS。
4. 为 `/api/blog/events` 关闭反向代理缓冲，并让读取超时大于 15 秒。
5. 保证应用可以连接 MongoDB；生产环境建议使用权限受限的专用数据库账号。
6. 部署到公网并持续开放注册时，将 `EMAIL_VERIFICATION_ENABLED` 与 `CAPTCHA_ENABLED` 设为 `true`，并确认邮件发送可用（生产使用 BillionMail 发送 API，见下文）；两项功能已实现，只是默认关闭。
7. 在 `.env` 设置足够长的随机 `RCON_TOKEN`。直接运行 JAR 时默认只监听 `127.0.0.1:8090`；Docker 发布端口时让容器内监听 `0.0.0.0`，但宿主端仍绑定 `127.0.0.1:8090`，远程管理通过 SSH 隧道进入。

Markdown 文件是运行时内容，不会打包到 JAR。保存文章即可发布，无需重新构建应用。

生产服务器通过 `scripts/sync-main.sh` 检查远程 `main`。脚本兼容 systemd 环境和 root 的 NVM 默认 Node.js，在独立临时 worktree 中执行 `npm ci` 与 `mvn verify`，验证成功后才原子替换线上 JAR并 `docker restart imzako` 重启 1Panel 容器；构建或健康检查失败会保留或恢复上一版本。`www-imzako-sync.timer` 默认每 10 分钟检查一次：

```bash
systemctl status www-imzako-sync.timer
systemctl list-timers www-imzako-sync.timer

# 立即检查；main 没有变化时直接退出
systemctl start www-imzako-sync.service

# 强制重新构建当前 main
/opt/1panel/www/java/www.imzako.com/scripts/sync-main.sh --force
```

生产 `.env` 与 Markdown 内容不进入 Git。服务器把 `BLOG_DIRECTORY` 指向工作区外的持久化目录，因此同步 `main` 不会覆盖线上文章；文章由 JAR 内置的 RCON 或等级 3 网页管理接口写入该目录。

## 项目结构

```text
blog/                                  Markdown 文章与本地图片
docs/frontend-auth.md                  用户、MongoDB、管理员与用户管理说明
docs/frontend-blog.md                  Markdown 博客、front matter 与实时更新说明
docs/rcon.md                           8090 RCON 协议、命令与安全部署说明
src/main/java/com/imzako/rcon/         8090 RCON 服务与管理命令
scripts/sync-main.sh                   定时同步、验证并部署 origin/main
deploy/                                systemd 同步服务与定时器
src/components/                        博客列表、主题开关、一言页脚等组件
src/composables/useAuth.ts             共享登录态、账号名与头像回退
src/composables/useTheme.ts            白天/夜间主题
src/config/sites.ts                    服务导航和扩展站点配置
src/router/                            Vue 页面路由
src/services/auth.ts                   登录、注册与账号管理接口
src/services/avatar.ts                 头像裁剪与压缩
src/services/blog.ts                   博客列表与正文
src/services/blogUpdates.ts            SSE 实时同步
src/services/markdown.ts               Markdown 渲染与代码高亮
src/views/                             Home、Blog、文章详情、Login、Account 等页面
src/main/java/com/imzako/auth/         MongoDB 用户、管理员、UID、验证码与认证接口
src/main/java/com/imzako/mail/         BillionMail 邮件发送与邮箱验证码
src/main/java/com/imzako/blog/         Markdown 扫描、文件监听和博客 API
src/main/java/com/imzako/web/          页面、健康检查与 CORS 配置
src/main/resources/application.properties
src/test/java/                         Java 单元与 HTTP 集成测试
.run/                                  IDEA 共享运行配置
.env                                   本地配置与密钥，不进入 Git
pom.xml                                Java 依赖和前后端联合构建
vite.config.ts                         Vite 与开发代理
vitest.config.ts                       前端测试配置
```

## BillionMail 验证码邮件

注册和账户验证可通过 BillionMail 的发送 API 投递验证码邮件。生产配置使用 `MAIL_TRANSPORT=billionmail`，发件人为 `noreply@imzako.com`。运行时只需要专用发送密钥：

```dotenv
MAIL_TRANSPORT=billionmail
BILLIONMAIL_API_URL=https://webmail.imzako.com
BILLIONMAIL_API_KEY=<专用发送 API 密钥>
BILLIONMAIL_TIMEOUT_MS=10000
SENDER=noreply@imzako.com
SENDER_NAME=imzako
```

邮件 HTML 模板位于 `src/main/resources/mail/verification-code.html`，支持 `username`、`code`、`minutes` 和 `site_url` 四个变量。修改模板后，在本地 `.env` 配置管理员 Token，再运行以下命令同步 BillionMail 模板和专用发送 API：

```powershell
.\scripts\configure-billionmail.ps1
```

`BILLIONMAIL_API_TOKEN` 仅供该同步脚本调用管理接口，不能提交到 Git，也不应放入生产应用的运行环境。常规测试不会发送真实邮件。

## 相关文档

- [上线清单](docs/deployment.md)
- [Markdown 博客与实时更新](docs/frontend-blog.md)
- [用户、MongoDB 与管理员账号](docs/frontend-auth.md)
- [Spring Boot Web](https://docs.spring.io/spring-boot/reference/web/servlet.html)
- [Spring Boot MongoDB](https://docs.spring.io/spring-boot/4.1-SNAPSHOT/reference/data/nosql.html)
- [Spring Security 密码存储](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Spring Data MongoDB](https://docs.spring.io/spring-data/mongodb/reference/)
- [Highlight.js 支持的语言](https://highlightjs.readthedocs.io/en/latest/supported-languages.html)
- [Vite 开发代理](https://vite.dev/config/server-options.html#server-proxy)
