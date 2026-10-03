# .env 配置设计规范

本文档定义 `www.imzako.com` 项目 `.env` 文件的变量命名、分类与必填规则。

> **约定**：变量名**加粗**且「必填」列标记 `*` 的为必填项——未配置会导致生产环境无法正常运行。

## 一、设计原则

1. 变量名统一使用**全大写 + 下划线**（UPPER_SNAKE_CASE），不使用点号或驼峰。
2. `.env` 位于项目根目录，由 Spring Boot `spring.config.import=file:.env[.properties]` **强制读取**，缺失即启动失败；`.env.local` 为可选本地覆盖。
3. 仓库提供脱敏模板 `.env.example`，敏感值以 `CHANGE_ME` 占位；首次部署复制为 `.env` 并填入真实值（随机串可用 `openssl rand -hex 32` 生成）。
4. 仅 `VITE_` 前缀变量会编译进前端并暴露给浏览器；**其余变量只供后端读取，`VITE_` 内严禁放密钥**。
5. 后端内部使用点号形式的 Spring 属性（如 `app.mail.transport`），其值取自同名大写环境变量（如 `MAIL_TRANSPORT`）。

## 二、变量分类

### 2.1 应用与域名

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| APP_NAME | | imzako | 应用名 |
| APP_ENV | | production | 取 `production` 时不再把验证码写进日志 |
| PUBLIC_DOMAIN | | www.example.com | 公开域名 |
| PUBLIC_URL | | https://www.example.com | 公开地址，用于验证码邮件里的站点链接 |
| GIT_DOMAIN | | git.example.com | Git 服务域名 |
| BLOG_PUBLIC_URL | | https://www.example.com/blog | 博客公开地址 |
| BACKEND_URL | | http://127.0.0.1:8080 | Vite 开发代理目标 |
| FRONTEND_DEV_URL | | http://localhost:5173 | 前端开发地址 |
| SERVER_PORT | | 8080 | 后端 HTTP 端口 |
| TRUST_PROXY | | true | 反向代理后采信 `X-Forwarded-For` |

### 2.2 RCON 管理端口

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| RCON_ENABLED | | true | 是否启用管理端口 |
| RCON_HOST | | 127.0.0.1 | 监听地址；Docker 发布端口时用 `0.0.0.0` |
| RCON_PORT | | 8090 | RCON TCP 端口 |
| **RCON_TOKEN** | * | CHANGE_ME | RCON 认证令牌；为空时端口拒绝所有认证 |

### 2.3 Cookie 与会话

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| COOKIE_NAME | | imzako_session | 会话 Cookie 名 |
| COOKIE_DOMAIN | | （空） | 预留；留空=仅限主机名 |
| COOKIE_PATH | | / | Cookie 路径 |
| COOKIE_HTTP_ONLY | | true | 是否 HttpOnly |
| COOKIE_SECURE | | true | HTTPS 下设为 true |
| COOKIE_SAME_SITE | | Lax | SameSite 策略 |
| COOKIE_SECRET | | CHANGE_ME | 预留（后续会话签名使用） |
| SESSION_TTL_SECONDS | | 604800 | 会话有效期（秒） |

### 2.4 访问令牌（预留）

本组变量当前**未被后端代码消费**，为后续 Token、邮箱验证与密码重置预留。

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| TOKEN_ISSUER | | www.example.com | 令牌签发者 |
| ACCESS_TOKEN_TTL_SECONDS | | 3600 | 访问令牌有效期（秒） |
| REFRESH_TOKEN_TTL_SECONDS | | 2592000 | 刷新令牌有效期（秒） |
| EMAIL_VERIFICATION_TOKEN_TTL_SECONDS | | 86400 | 邮箱验证令牌有效期（秒） |
| PASSWORD_RESET_TOKEN_TTL_SECONDS | | 3600 | 密码重置令牌有效期（秒） |
| TOKEN_SECRET | | CHANGE_ME | 令牌签名密钥 |

### 2.5 MongoDB

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| **MONGODB_URI** | * | mongodb://user:pass@host:27017/imzako?authSource=admin | 连接串（凭据可内嵌） |
| MONGODB_DATABASE | | imzako | 业务库名 |
| MONGODB_USERNAME | | CHANGE_ME | 预留；当前仅使用 MONGODB_URI |
| MONGODB_PASSWORD | | CHANGE_ME | 预留；当前仅使用 MONGODB_URI |
| MONGODB_AUTH_SOURCE | | admin | 认证库 |
| MONGODB_CONNECT_TIMEOUT_MS | | 10000 | 连接超时（毫秒） |
| MONGODB_SOCKET_TIMEOUT_MS | | 10000 | 读写超时（毫秒） |

### 2.6 CORS 跨域

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| ALLOWED_ORIGINS | | http://localhost:5173,https://www.example.com,… | 逗号分隔的精确来源，末尾不带斜杠 |
| ALLOWED_METHODS | | GET,POST,PUT,PATCH,DELETE,OPTIONS | 允许的方法 |
| ALLOWED_HEADERS | | Accept,Authorization,Content-Type,X-CSRF-Token | 允许的请求头 |
| ALLOW_CREDENTIALS | | true | 是否携带凭据 |

### 2.7 安全与防滥用（预留）

本组变量当前**未被后端代码消费**，为后续 CSRF、密码策略、登录锁定与限流预留。

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| CSRF_ENABLED | | true | CSRF 开关 |
| PASSWORD_MIN_LENGTH | | 12 | 密码最小长度 |
| BCRYPT_COST | | 12 | bcrypt 成本因子 |
| LOGIN_MAX_ATTEMPTS | | 5 | 登录最大失败次数 |
| LOGIN_LOCK_SECONDS | | 900 | 登录锁定时间（秒） |
| RATE_LIMIT_ENABLED | | true | 限流开关 |
| RATE_LIMIT_WINDOW_SECONDS | | 60 | 限流窗口（秒） |
| RATE_LIMIT_MAX_REQUESTS | | 100 | 窗口内最大请求数 |

### 2.8 邮件发送（BillionMail API）

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| MAIL_TRANSPORT | | billionmail | 传输方式：`billionmail` / `smtp` |
| SENDER | | noreply@example.com | 发件人地址 |
| SENDER_NAME | | imzako | 发件人名称 |
| BILLIONMAIL_API_URL | | https://webmail.example.com | BillionMail 接口地址 |
| **BILLIONMAIL_API_KEY** | * | CHANGE_ME | BillionMail 专用发送 API 密钥 |
| BILLIONMAIL_TIMEOUT_MS | | 10000 | 请求超时（毫秒） |
| BILLIONMAIL_API_TOKEN | | CHANGE_ME | 可选；仅供 `scripts/configure-billionmail.*` 使用，运行时不需要 |

### 2.9 账号门禁

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| EMAIL_VERIFICATION_ENABLED | | true | 邮箱验证开关 |
| CAPTCHA_ENABLED | | false | 登录图形验证码开关 |
| EMAIL_VERIFICATION_TTL_SECONDS | | 600 | 验证码有效期（秒） |
| EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS | | 60 | 重发冷却（秒） |
| EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR | | 5 | 每滚动小时重发上限 |
| LOGIN_CAPTCHA_THRESHOLD | | 3 | 触发验证码的失败次数 |
| LOGIN_FAILURE_WINDOW_SECONDS | | 900 | 失败计数窗口（秒） |
| LOGIN_FAILURE_MAX_TRACKED_KEYS | | 10000 | 失败计数键上限 |
| REGISTRATION_MAX_REQUESTS_PER_HOUR | | 10 | 每小时注册上限 |
| VERIFICATION_RESEND_MAX_REQUESTS_PER_HOUR | | 20 | 每小时验证码请求上限 |
| CAPTCHA_MAX_REQUESTS_PER_MINUTE | | 30 | 每分钟验证码请求上限 |
| PUBLIC_RATE_LIMIT_MAX_TRACKED_KEYS | | 10000 | 公开接口限流键上限 |

### 2.10 前端站点（浏览器可见）

仅 `VITE_` 前缀变量会暴露给浏览器；修改后需**重新构建前端**。

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| VITE_SITE_NAME | | imzako | 站点名 |
| VITE_PUBLIC_DOMAIN | | www.example.com | 公开域名 |
| VITE_GIT_URL | | https://git.example.com | Git 入口 |
| VITE_BLOG_URL | | https://www.example.com/blog | 博客入口 |
| VITE_MAIL_URL | | https://mail.example.com | 邮箱入口 |
| VITE_EXTRA_SITES_JSON | | [] | 扩展站点（对象数组，见 `.env.example` 注释） |

### 2.11 博客

| 变量 | 必填 | 推荐值 | 说明 |
| --- | :-: | --- | --- |
| BLOG_DIRECTORY | | blog | Markdown 源目录（相对后端工作目录） |
| VITE_BLOG_API_URL | | /api/blog/posts | 博客列表接口 |
| VITE_BLOG_POST_URL | | /api/blog/post | 博客正文接口 |
| VITE_BLOG_EVENTS_URL | | /api/blog/events | 博客 SSE 实时通知 |
| VITE_LOGIN_ENDPOINT | | /api/auth/login | 登录接口 |
| VITE_REGISTER_ENDPOINT | | /api/auth/register | 注册接口 |
| VITE_HITOKOTO_API_URL | | https://v1.hitokoto.cn/?encode=json&max_length=60 | 一言页脚接口 |

## 三、必填项清单

| 变量 | 分类 | 未配置的后果 |
| --- | --- | --- |
| **MONGODB_URI** | MongoDB | 回退到 `127.0.0.1:27017`，生产环境无法连库 |
| **BILLIONMAIL_API_KEY** | 邮件发送 | 验证码邮件无法发送（`hasBillionMailCredentials()` 为 false） |
| **RCON_TOKEN** | RCON | 8090 端口拒绝一切认证，无法执行管理命令 |
