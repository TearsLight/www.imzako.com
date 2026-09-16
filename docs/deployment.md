# 上线清单

本文档记录把 `www.imzako.com` 部署到生产环境所需要的配置与操作。`.env` 当前已经按生产环境配置好，本地开发请用 `.env.local` 覆盖（见文末）。

## 分支与自动发布

- `dev/test` 用于开发和测试。
- `main` 是唯一的生产发布分支。
- 服务器工作区固定为 `main`，不在服务器直接编辑受 Git 跟踪的文件。

服务器的 `www-imzako-sync.timer` 每 10 分钟触发一次 `scripts/sync-main.sh`。脚本先获取 `origin/main`，在独立临时 worktree 中安装前端依赖并执行完整 `mvn verify`；只有构建和测试通过后才原子替换 JAR、重启应用并执行健康检查。失败时线上进程继续使用旧 JAR，或自动回滚到上一 JAR。

```bash
systemctl status www-imzako-sync.timer
systemctl list-timers www-imzako-sync.timer
systemctl start www-imzako-sync.service
journalctl -u www-imzako-sync.service -n 200 --no-pager
```

生产 `.env` 保留在工作区但不进入 Git。生产 Markdown 使用工作区外的持久化目录，例如：

```dotenv
BLOG_DIRECTORY=/opt/1panel/www/java/www.imzako.com-data/blog
```

`scripts/new-post.mjs` 会读取 `.env`，因此仍可在服务器工作区运行 `npm run blog -- -n "文章名称"`，同步代码时不会覆盖线上文章。

## 一、构建产物

```powershell
.\mvnw.cmd clean verify
```

这一步会依次执行：

1. `npm run build`（含 `vue-tsc --build` 类型检查与 `vite build`），产物复制到 `target/classes/static`。
2. `npm run test:unit -- --run`（Vitest 前端单元测试）。
3. Java 测试。
4. 打包为 `target/imzako-0.0.1-SNAPSHOT.jar`（自带 Tomcat，无需外置容器）。

产物自检：

```powershell
# 必须存在 Main-Class，说明是 Spring Boot 可执行 jar
.\mvnw.cmd -o -q help:evaluate -Dexpression=project.build.finalName -DforceStdout
java -jar target\imzako-0.0.1-SNAPSHOT.jar
```

## 二、运行环境要求

| 项目     | 要求                                                                 |
| -------- | -------------------------------------------------------------------- |
| JDK      | 21（`java -version` 必须是 21；本机默认 `java` 可能是 1.8）          |
| MongoDB  | 可达，且账号有 `imzako` 库的读写权限                                 |
| SMTP     | `smtp.geeksend.io:587` 出网可达（用于注册邮箱验证码）                |
| 目录     | 运行目录下保留外部 `blog/`，或设置 `BLOG_DIRECTORY` 为持久化绝对路径 |
| 反向代理 | 可选，但 `/api/blog/events` 必须关闭缓冲（见第五节）                 |

JAR 内已包含前端产物、MongoDB 驱动与邮件依赖，运行时不依赖 Node 与 Vite。

## 三、关键配置（.env）

`.env` 不进入版本库，必须随部署单独提供。当前已按生产设置：

| 配置                             | 生产值                        | 说明                                                    |
| -------------------------------- | ----------------------------- | ------------------------------------------------------- |
| `APP_ENV`                        | `production`                  | 取 `production` 时**不再把验证码写进日志**              |
| `COOKIE_SECURE`                  | `true`                        | 必须配合 HTTPS；若临时用 HTTP 访问会无法登录            |
| `TRUST_PROXY`                    | `true`                        | 由反向代理转发时设为 `true`，让服务端拿到真实客户端地址 |
| `VITE_BLOG_URL`                  | `https://www.imzako.com/blog` | **构建期**写入前端产物，改动后必须重新 `npm run build`  |
| `ALLOWED_ORIGINS`                | 含 `https://www.imzako.com`   | 跨域白名单                                              |
| `app.email-verification.enabled` | `true`                        | 注册必须验证邮箱；关闭则注册后直接登录                  |
| `app.captcha.enabled`            | `false`                       | 登录图形验证码开关；需要时改为 `true`                   |

`VITE_` 前缀的变量会被编译进前端产物，**不是运行时配置**。修改后一定要重新构建，否则线上仍是旧值（例如指向 `localhost`）。

## 四、启动

```powershell
java -jar target\imzako-0.0.1-SNAPSHOT.jar
```

正常工作时应看到：

```text
MongoDB account schema ready: users, admin_users, sequences, email_verifications
Markdown blog ready: <绝对路径>\blog (N articles)
Tomcat started on port 8080 (http) with context path '/'
Started ImzakoApplication in 1.8 seconds
```

启动时自动执行的迁移与初始化（幂等，可反复启动）：

- 创建缺失的集合与索引。
- 确保 `email_verifications.expiresAt` 使用到期即删除的 TTL 索引；若旧同名索引没有 TTL 属性，会自动替换。
- 删除旧版本遗留的 `username_unique` 唯一索引（用户名现已允许重复）。
- 把没有 `emailVerified` 字段的存量账号标记为已验证，避免开启邮箱验证后老账号失联。

## 五、反向代理

`/api/blog/events` 是 SSE 长连接，代理必须关闭该路径的缓冲，并让读取超时大于 15 秒心跳间隔，否则文章更新不会实时推送：

```nginx
location /api/blog/events {
    proxy_pass http://127.0.0.1:8080;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 120s;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

同时代理 `/api/` 与其余路径到同一端口即可。设置了 `TRUST_PROXY=true` 时才会采信 `X-Forwarded-For`，因此**不要把应用端口直接暴露到公网**，否则客户端可以伪造该头绕过按地址的登录失败计数。

## 六、上线后自检

```powershell
curl -s http://127.0.0.1:8080/api/health
# {"status":"UP","application":"www.imzako.com"}

curl -s -o NUL -w "%{http_code}`n" http://127.0.0.1:8080/
curl -s "http://127.0.0.1:8080/api/blog/posts?limit=10"
```

- `/`、`/blog`、`/login`、`/register`、`/account` 都应返回 200 且是同一份 `index.html`。
- `/api/missing`、`/missing.js`、`/missing-page` 都应返回 404，不能返回 HTML。
- 从外网打开首页，确认：
  - 浏览器地址栏为 HTTPS，登录后 Cookie 带有 `Secure` 标记。
  - 首页「Git」「博客」两个入口指向 `https://git.imzako.com` 与 `https://www.imzako.com/blog`（若指向 `localhost`，说明构建时 `.env` 的值不对，需重新构建）。
  - 注册一个新账号，确认能收到验证码邮件；输入验证码后才能登录。
  - 保存一篇 Markdown 到 `blog/`，确认已打开的页面无需刷新即自动更新。

## 七、本地开发覆盖

`.env` 现在是生产值。本地开发建议新建一个**不进入版本库**的 `.env.local`（`.gitignore` 已通过 `*.local` 覆盖），用于覆盖需要改回本地值的项：

```dotenv
# .env.local —— 仅本地开发，不提交
VITE_BLOG_URL=http://localhost:8080/blog
COOKIE_SECURE=false
TRUST_PROXY=false
APP_ENV=development
```

Vite 会以比 `.env` 更高的优先级加载 `.env.local`（两者都会被读取，`.local` 覆盖前者），Spring Boot 通过 `application.properties` 里的 `optional:file:.env.local[.properties]` 一并导入。若不想用覆盖文件，也可以直接把上面四行改回本地值，但**记得上线前改回来**。

## 八、已知限制

- **登录失败计数、公开接口限流与图形验证码保存在单实例内存中**，并且都有数量上限，不会随未受信输入无限增长。多实例部署时各实例的计数互不共享；将来横向扩容需要改为共享存储（如 Redis 或 MongoDB）。
- 密码重置（忘记密码）尚未实现，`PASSWORD_RESET_*` 与 SMTP 已就绪，属于后续功能。
- 开启 `app.captcha.enabled` 后，图形验证码答案只存在服务端内存，重启会失效，用户重新获取即可。
- 「第一篇注册用户 UID」为 `1000001`，管理员通过向 `admin_users` 写入 UID 授予，没有公开接口。
