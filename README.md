# www.imzako.com

`www.imzako.com` 是一个个人服务门户与文件驱动博客。前端使用 Vue 3、TypeScript 和 Vite，后端使用 Java 21、Spring Boot 4.1.1 与 MongoDB。

主页 `/` 展示服务导航，继续向下滚动即可进入博客区域并浏览最近更新的 10 篇文章。顶部导航、登录与注册入口、白天/夜间模式和一言页脚在所有页面共用。

## 当前功能

### 主页与全局界面

- `/` 对应 `HomePage`，包含题头、个人服务入口和预留站点卡片。
- Git 服务默认指向 `https://git.imzako.com`。
- 博客默认指向 `http://localhost:8080/blog`，生产地址预留为 `https://www.imzako.com/blog`。
- 可通过 `VITE_EXTRA_SITES_JSON` 增加其他站点，无需修改页面模板。
- 顶部导航在所有路由共用，包含首页、Git、博客、用户登录和主题开关。
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
- `/` 下方与 `/blog` 共用文章列表，最多显示最近更新的 10 篇。
- `/blog/posts/{slug}` 渲染文章详情，支持子目录，例如 `blog/notes/first.md` 对应 `/blog/posts/notes/first`。
- 第一个一级标题作为文章标题；没有一级标题时使用文件名。
- 摘要从正文自动提取，第一层子目录作为分类，文件修改时间作为排序与更新时间。
- 支持标题、列表、引用、表格、链接、代码块和相对路径图片。
- 本地图片支持 PNG、JPEG、GIF、WebP 和 AVIF，并限制在博客目录内读取。
- 单篇 Markdown 最大 1 MiB，超过限制的文件不会进入文章索引。
- 原始 HTML 默认关闭，渲染结果经 DOMPurify 清理后再插入页面。

保存 Markdown 后，无需重新构建或刷新浏览器，已打开的列表和正文会自动同步。详细说明见 [Markdown 博客文档](docs/frontend-blog.md)。

#### 代码语法高亮

代码块使用 Highlight.js。为代码围栏指定语言即可显示高亮：

````markdown
```typescript
const message: string = 'Hello, Markdown!'
console.log(message)
```
````

当前按需加载常用语言集合，包括 JavaScript、TypeScript、Java、Python、Go、Rust、C/C++、C#、Kotlin、Swift、PHP、Ruby、SQL、Shell、CSS、JSON、YAML 和 Markdown。没有指定语言或语言无法识别时会自动检测。高亮配色同时适配白天和夜间主题。

### 用户、登录与管理员

- `/login` 提供登录前端，支持用户名或邮箱。
- `/register` 开放普通用户注册，包含邮箱、用户名、密码确认、性别和生日字段的前端校验。
- `POST /api/auth/register` 创建普通用户并建立会话。
- 注册成功后直接进入登录会话，并显示新账户 UID。
- 首位注册用户的 UID 为 `1000001`，后续注册通过 MongoDB 原子序列递增。
- 邮箱、用户名和 UID 均有唯一索引；邮箱和用户名使用规范化检索键避免大小写重复。
- 密码使用 Spring Security `DelegatingPasswordEncoder`，当前写入带随机盐的 bcrypt 单向哈希。
- API 和页面不会返回密码哈希、MongoDB `_id` 或数据库凭据。
- 登录通过 HttpOnly Session Cookie 保持，名称、路径、Secure、SameSite 和有效期由 `.env` 配置。
- `admin_users` 保存管理员 UID。UID 出现在该集合中时，下一次登录会获得 `USER` 与 `ADMIN` 角色。
- 公开注册不会授予管理员身份，也没有公开的管理员授权接口。

MongoDB 使用业务数据库 `imzako`，启动时创建并检查：

| 集合          | 用途                                 |
| ------------- | ------------------------------------ |
| `users`       | 网站用户、资料与密码哈希             |
| `admin_users` | 管理员 UID 名单                      |
| `sequences`   | UID 原子递增序列，初始值为 `1000000` |

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
| `http://localhost:8080/`         | Spring Boot 打包后的前端与 API |

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
| `GET /blog`、`GET /blog/`         | 最近更新的最多 10 篇文章                  |
| `GET /blog/posts/{slug}`          | Markdown 文章详情，支持嵌套路径与直接刷新 |
| `GET /login`、`GET /login/`       | 用户登录页面                              |
| `GET /register`、`GET /register/` | 用户注册页面                              |
| `GET /about`、`GET /about/`       | 兼容旧地址，前端重定向至 `/`              |

只有已知页面路由会转发至 Vue。未知页面、API 和静态资源返回 404，避免把错误请求误返回为 HTML。

## 后端 API

### 通用与博客

| 接口                                               | 说明                                    |
| -------------------------------------------------- | --------------------------------------- |
| `GET /api/health`                                  | 返回应用存活状态                        |
| `GET /api/blog/posts?limit=10`                     | 返回文章列表和当前 revision，最多 10 篇 |
| `GET /api/blog/post?slug=notes/first`              | 返回文章元数据、Markdown 与更新时间     |
| `GET /api/blog/events`                             | SSE `sync`、`changed`、`heartbeat` 事件 |
| `GET /api/blog/assets?path=notes/images/photo.png` | 读取博客目录内允许的图片                |

### 账号

| 接口                      | 说明                                  |
| ------------------------- | ------------------------------------- |
| `POST /api/auth/register` | 注册普通用户；成功返回 201 并建立会话 |
| `POST /api/auth/login`    | 以用户名或邮箱登录                    |
| `GET /api/auth/me`        | 获取当前会话用户；未登录返回 401      |
| `POST /api/auth/logout`   | 注销并使当前会话失效，返回 204        |

注册示例：

```json
{
  "email": "member@example.com",
  "username": "member",
  "password": "a-long-private-password",
  "gender": "undisclosed",
  "birthday": "2000-01-02"
}
```

`gender` 可使用 `male`、`female`、`other`、`undisclosed`。`birthday` 可省略，但提供时必须早于当前日期。

## `.env` 配置

根目录 `.env` 集中保存项目运行配置，并已被 Git 忽略。Spring Boot 通过 `spring.config.import` 读取该文件；Vite 读取 `BACKEND_URL`，并只向浏览器暴露 `VITE_` 前缀的变量。

不要把 `.env`、数据库密码、Cookie 密钥、Token 密钥或 SMTP 密码提交到版本库。

| 分组          | 变量                                                                                                                                                                           | 当前用途                                                      |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------- |
| 应用与域名    | `APP_NAME`、`APP_ENV`、`PUBLIC_DOMAIN`、`PUBLIC_URL`、`GIT_DOMAIN`、`BLOG_PUBLIC_URL`                                                                                          | 服务端与部署环境信息                                          |
| 本地端口      | `BACKEND_URL`、`FRONTEND_DEV_URL`、`SERVER_PORT`、`TRUST_PROXY`                                                                                                                | 后端端口与 Vite API 代理                                      |
| Cookie 与会话 | `COOKIE_NAME`、`COOKIE_DOMAIN`、`COOKIE_PATH`、`COOKIE_HTTP_ONLY`、`COOKIE_SECURE`、`COOKIE_SAME_SITE`、`COOKIE_SECRET`、`SESSION_TTL_SECONDS`                                 | 登录会话；当前直接使用除 Domain 与 Secret 外的 Cookie 配置    |
| Token         | `TOKEN_ISSUER`、`ACCESS_TOKEN_TTL_SECONDS`、`REFRESH_TOKEN_TTL_SECONDS`、`EMAIL_VERIFICATION_TOKEN_TTL_SECONDS`、`PASSWORD_RESET_TOKEN_TTL_SECONDS`、`TOKEN_SECRET`            | 为后续 Token、邮箱验证与密码重置预留                          |
| MongoDB       | `MONGODB_URI`、`MONGODB_DATABASE`、`MONGODB_USERNAME`、`MONGODB_PASSWORD`、`MONGODB_AUTH_SOURCE`、`MONGODB_CONNECT_TIMEOUT_MS`、`MONGODB_SOCKET_TIMEOUT_MS`                    | 用户、管理员与 UID 序列；后端当前使用 `MONGODB_URI`           |
| 跨域          | `ALLOWED_ORIGINS`、`ALLOWED_METHODS`、`ALLOWED_HEADERS`、`ALLOW_CREDENTIALS`                                                                                                   | 当前启用允许来源与携带凭据；方法和请求头采用后端安全白名单    |
| 安全预留      | `CSRF_ENABLED`、`PASSWORD_MIN_LENGTH`、`BCRYPT_COST`、`LOGIN_MAX_ATTEMPTS`、`LOGIN_LOCK_SECONDS`、`RATE_LIMIT_ENABLED`、`RATE_LIMIT_WINDOW_SECONDS`、`RATE_LIMIT_MAX_REQUESTS` | 后续 CSRF、密码策略、登录锁定和限流扩展；尚未全部接入运行逻辑 |
| SMTP          | `SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASS`、`SMTP_TLS`、`SMTP_AUTH`、`SMTP_CONNECTION_TIMEOUT_MS`、`SMTP_TIMEOUT_MS`、`SENDER`、`SENDER_NAME`                          | 邮箱验证、通知与密码重置预留，当前不发送邮件                  |
| 前端站点      | `VITE_SITE_NAME`、`VITE_PUBLIC_DOMAIN`、`VITE_GIT_URL`、`VITE_BLOG_URL`、`VITE_EXTRA_SITES_JSON`                                                                               | 品牌、域名和服务导航                                          |
| 前端接口      | `VITE_LOGIN_ENDPOINT`、`VITE_REGISTER_ENDPOINT`、`VITE_HITOKOTO_API_URL`                                                                                                       | 登录、注册与一言接口地址                                      |
| 博客          | `BLOG_DIRECTORY`、`VITE_BLOG_API_URL`、`VITE_BLOG_POST_URL`、`VITE_BLOG_EVENTS_URL`                                                                                            | Markdown 目录、列表、正文与实时通知                           |

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

Maven 在 `generate-resources` 阶段执行 `npm run build`，其中包含 TypeScript 类型检查，然后将 `dist` 复制到 JAR 的静态资源目录。

最近一次验证结果：

- 40 项前端测试通过。
- 22 项 Java 测试通过。
- Markdown 文件新增、修改、替换保存、重命名、删除和 SSE 重连均使用真实临时文件与 HTTP 连接测试。
- 密码哈希、UID 分配、普通用户与管理员角色判定包含独立测试。
- `npm audit` 未发现漏洞。

## 打包与部署

构建完成后运行：

```powershell
java -jar target\imzako-0.0.1-SNAPSHOT.jar
```

运行 JAR 只需要 JDK 21，不需要 Node、Vite 或外部 Tomcat。

部署时需要：

1. 在应用工作目录保留外部 `blog/`，或将 `BLOG_DIRECTORY` 设置为持久化绝对路径。
2. 提供生产环境 `.env` 或等价环境变量。
3. 将 `COOKIE_SECURE` 设置为 `true`，并使用 HTTPS。
4. 为 `/api/blog/events` 关闭反向代理缓冲，并让读取超时大于 15 秒。
5. 保证应用可以连接 MongoDB；生产环境建议使用权限受限的专用数据库账号。
6. 部署到公网并持续开放注册时，接入 SMTP 邮箱验证、CSRF 防护、注册限流和登录失败锁定。

Markdown 文件是运行时内容，不会打包到 JAR。保存文章即可发布，无需重新构建应用。

## 项目结构

```text
blog/                                  Markdown 文章与本地图片
docs/frontend-auth.md                  用户、MongoDB 与管理员说明
docs/frontend-blog.md                  Markdown 博客与实时更新说明
src/components/                        博客列表、主题开关、一言页脚等组件
src/composables/                       全局可复用状态与主题逻辑
src/config/sites.ts                    服务导航和扩展站点配置
src/router/                            Vue 页面路由
src/services/                          登录、博客、实时更新和 Markdown 渲染
src/views/                             Home、Blog、文章详情、Login 等页面
src/main/java/com/imzako/auth/         MongoDB 用户、管理员、UID 与认证接口
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

## 相关文档

- [Markdown 博客与实时更新](docs/frontend-blog.md)
- [用户、MongoDB 与管理员账号](docs/frontend-auth.md)
- [Spring Boot Web](https://docs.spring.io/spring-boot/reference/web/servlet.html)
- [Spring Boot MongoDB](https://docs.spring.io/spring-boot/4.1-SNAPSHOT/reference/data/nosql.html)
- [Spring Security 密码存储](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [Spring Data MongoDB](https://docs.spring.io/spring-data/mongodb/reference/)
- [Highlight.js 支持的语言](https://highlightjs.readthedocs.io/en/latest/supported-languages.html)
- [Vite 开发代理](https://vite.dev/config/server-options.html#server-proxy)
