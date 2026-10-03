# 上线清单

本文档记录把 `www.imzako.com` 作为单个可执行 JAR 部署到 1Panel Java Docker 运行环境所需的配置、发布和回滚检查。生产 `.env` 不进入仓库；本地开发可用 `.env.local` 覆盖（见文末）。

## 分支与自动发布

- `dev/test` 用于开发和测试。
- `main` 是唯一的生产发布分支。
- 服务器工作区固定为 `main`，不在服务器直接编辑受 Git 跟踪的文件。

服务器的 `www-imzako-sync.timer` 每 10 分钟触发一次 `scripts/sync-main.sh`。脚本先获取 `origin/main`，在独立临时 worktree 中安装前端依赖并执行完整 `mvn verify`；只有构建和测试通过后才原子替换 JAR、执行 `docker restart imzako` 重启 1Panel 容器并做健康检查。失败时线上进程继续使用旧 JAR，或自动回滚到上一 JAR。

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

文章由运行中 JAR 的 RCON 或等级 3 网页管理接口写入该目录，不再要求服务器安装 npm。

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
| 邮件     | BillionMail 发送 API（需要独立的 `BILLIONMAIL_API_KEY`）                    |
| 目录     | 运行目录下保留外部 `blog/`，或设置 `BLOG_DIRECTORY` 为持久化绝对路径 |
| 反向代理 | 可选，但 `/api/blog/events` 必须关闭缓冲（见第五节）                 |

JAR 内已包含前端产物、MongoDB 驱动与邮件依赖，运行时不依赖 Node 与 Vite。

## 三、关键配置（.env）

`.env` 不进入版本库，必须与 JAR 放在同一工作目录，或改用等价的容器环境变量。仓库根目录提供已脱敏的 `.env.example` 模板，复制为 `.env` 后填入真实值。推荐生产设置：

| 配置                             | 生产值                        | 说明                                                    |
| -------------------------------- | ----------------------------- | ------------------------------------------------------- |
| `APP_ENV`                        | `production`                  | 取 `production` 时**不再把验证码写进日志**              |
| `COOKIE_SECURE`                  | `true`                        | 必须配合 HTTPS；若临时用 HTTP 访问会无法登录            |
| `TRUST_PROXY`                    | `true`                        | 由反向代理转发时设为 `true`，让服务端拿到真实客户端地址 |
| `VITE_BLOG_URL`                  | `https://www.imzako.com/blog` | **构建期**写入前端产物，改动后必须重新 `npm run build`  |
| `ALLOWED_ORIGINS`                | 含 `https://www.imzako.com`   | 跨域白名单                                              |
| `EMAIL_VERIFICATION_ENABLED` | `true`                        | 注册必须验证邮箱；关闭则注册后直接登录                  |
| `CAPTCHA_ENABLED`            | `false`                       | 登录图形验证码开关；需要时改为 `true`                   |
| `RCON_ENABLED`                   | `true`                        | 启用独立管理端口                                        |
| `RCON_HOST`                      | 见下文                        | 直接运行用 `127.0.0.1`；Docker 端口发布用 `0.0.0.0`     |
| `RCON_PORT`                      | `8090`                        | RCON 管理端口                                           |
| `RCON_TOKEN`                     | 随机长令牌                    | 必填；未设置时端口拒绝所有认证                          |

`VITE_` 前缀的变量会被编译进前端产物，**不是运行时配置**。修改后一定要重新构建，否则线上仍是旧值（例如指向 `localhost`）。

## 四、运行

生产环境由 1Panel「网站 → Java 运行环境」使用 Java 21 Docker 镜像运行。建议配置如下：

| 1Panel 项目项 | 建议值                                                                    |
| ------------- | ------------------------------------------------------------------------- |
| 名称          | `imzako`                                                                  |
| Java / 镜像   | Java 21 / `1panel/java:21`                                                |
| 代码目录      | 独立目录，内含一个 JAR 和同级 `.env`                                      |
| 工作目录      | 代码目录在容器内的挂载点，例如 `/app`                                     |
| 启动命令      | `java -jar /app/imzako-0.0.1-SNAPSHOT.jar`                                |
| Web 端口      | 宿主 `127.0.0.1:8080` → 容器 `8080`                                       |
| RCON 端口     | 宿主 `127.0.0.1:8090` → 容器 `8090`；仅内网联调时才绑定受信任的局域网地址 |
| 博客目录      | 挂载宿主持久化目录，并让 `BLOG_DIRECTORY` 指向容器内路径                  |
| 重启策略      | `unless-stopped`                                                          |

Spring Boot 按**进程工作目录**查找 `.env`，所以 1Panel 的工作目录必须是 JAR 与 `.env` 所在目录。Web 端口只绑定宿主回环地址，再由 OpenResty 反代，避免绕过 HTTPS、Cookie 与可信代理边界。

Docker 发布 RCON 时，容器内进程必须监听容器网卡，因此 `.env` 使用 `RCON_HOST=0.0.0.0`；安全边界由宿主的 `127.0.0.1:8090` 绑定提供。不要把宿主 8090 发布到 `0.0.0.0` 或公网。若不发布 RCON 端口，则可保留默认 `RCON_HOST=127.0.0.1` 并只在容器内使用。

重启与查看日志：

```bash
docker restart imzako
docker logs -f imzako
```

本地开发仍可直接 `java -jar target\imzako-0.0.1-SNAPSHOT.jar`。JAR 与 `.env` 放在同一工作目录启动；正常工作时应看到：

```text
MongoDB account schema ready: users, admin_users, user_bans, sequences, email_verifications
Markdown blog ready: <绝对路径>\blog (N articles)
RCON listening on 127.0.0.1:8090
Tomcat started on port 8080 (http) with context path '/'
Started ImzakoApplication in 1.8 seconds
```

启动时自动执行的迁移与初始化（幂等，可反复启动）：

- 创建缺失的集合与索引。
- 确保 `email_verifications.expiresAt` 使用到期即删除的 TTL 索引；若旧同名索引没有 TTL 属性，会自动替换。
- 删除旧版本遗留的 `username_unique` 唯一索引（用户名现已允许重复）。
- 把没有 `emailVerified` 字段的存量账号标记为已验证，避免开启邮箱验证后老账号失联。
- 为临时封禁创建 `user_bans.bannedUntil` TTL 索引；到期记录由 MongoDB 自动清理。

RCON 是 UTF-8 行协议。连接 8090 后先发送 `auth <RCON_TOKEN>`，再发送管理命令：

```text
imzako new "博客名" --tags "技术 Java" --publish
op 1000001 3
ban 1000002 24 "刷屏"
pardon 1000002
user 1000002
```

端口本身不提供 TLS。直接运行 JAR 时保持 `RCON_HOST=127.0.0.1`；Docker 场景按上一节使用“容器内 `0.0.0.0` + 宿主 `127.0.0.1`”的双层绑定，再通过 SSH 隧道连接。协议细节与命令说明见 [RCON 管理文档](rcon.md)。

### 离线 1Panel 节点

无公网出口的节点必须在创建 Java 运行环境前准备好以下内容：

1. 导入 1Panel Java 运行环境所需的**精确镜像标签**（例如 `1panel/java:21`），并用 `docker image inspect` 确认存在。
2. 在有网络的同版本 1Panel 上导出或同步 Java 运行环境模板元数据，确保面板不会因模板仍处于“待更新”状态而重复尝试下载。
3. 预先准备 JAR、`.env` 和持久化博客目录；离线运行阶段不需要 npm，也不会下载前端依赖。
4. 确认节点 DNS 指向内网解析器，并从测试客户机验证站点域名解析到部署主机。

优先恢复受控的软件源/镜像源或通过面板同步模板。直接修改 1Panel 数据库不是标准部署步骤；若故障恢复时不得不这样做，应先停止相关服务、备份数据库、记录变更，并在面板与容器状态一致后立即复核。

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
curl -s "http://127.0.0.1:8080/api/blog/posts?limit=200&q=Markdown"
```

- `/`、`/blog`、`/login`、`/register`、`/account` 都应返回 200 且是同一份 `index.html`。
- `/api/missing`、`/missing.js`、`/missing-page` 都应返回 404，不能返回 HTML。
- 从外网打开首页，确认：
  - 浏览器地址栏为 HTTPS，登录后 Cookie 带有 `Secure` 标记。
  - 首页「Git」「博客」「邮箱」三个入口分别指向 `https://git.imzako.com`、`https://www.imzako.com/blog` 与 `https://mail.imzako.com`（若指向 `localhost`，说明构建时 `.env` 的值不对，需重新构建）。
  - 注册一个新账号，确认能收到验证码邮件；输入验证码后才能登录。
  - 保存一篇 Markdown 到 `blog/`，确认已打开的页面无需刷新即自动更新。
  - 打开 `/blog`，确认搜索框能按标题、摘要或分类筛选。
- 从可信管理入口连接 8090，确认先收到 RCON banner，未认证命令被拒绝，`auth <token>` 后 `help` 和 `user UID` 可用。
- 使用临时等级 3 账号验证博客新增、修改、删除，再封禁该账号并确认写请求返回 403；测试结束后删除临时文章并恢复账号状态。

## 七、推送与发布前检查

```powershell
git status --short
git diff --check
.\mvnw.cmd clean verify
git diff --stat
```

提交前还要确认：

- `.env`、私钥、RCON Token、1Panel API Key、数据库/邮件凭据没有出现在暂存区或文档中。
- `target/`、`dist/`、`node_modules/` 与运行时 `blog/` 内容没有误提交。
- `scripts/new-post.mjs` 的删除与新的 Java RCON 实现一起提交，避免保留两套互相冲突的建文入口。
- 先推送到 `dev/test` 并完成测试环境自检；只有 JAR、登录、搜索、RCON 和等级 3 博客管理均通过后，再快进合并到 `main`。

## 八、本地开发覆盖

本地开发建议新建一个**不进入版本库**的 `.env.local`（`.gitignore` 已通过 `*.local` 覆盖），用于覆盖生产项：

```dotenv
# .env.local —— 仅本地开发，不提交
VITE_BLOG_URL=http://localhost:8080/blog
COOKIE_SECURE=false
TRUST_PROXY=false
APP_ENV=development
```

Vite 会以比 `.env` 更高的优先级加载 `.env.local`（两者都会被读取，`.local` 覆盖前者），Spring Boot 通过 `application.properties` 里的 `optional:file:.env.local[.properties]` 一并导入。若不想用覆盖文件，也可以直接把上面四行改回本地值，但**记得上线前改回来**。

## 九、已知限制

- **登录失败计数、公开接口限流与图形验证码保存在单实例内存中**，并且都有数量上限，不会随未受信输入无限增长。多实例部署时各实例的计数互不共享；将来横向扩容需要改为共享存储（如 Redis 或 MongoDB）。
- 密码重置（忘记密码）尚未实现，`PASSWORD_RESET_*` 与邮件发送已就绪，属于后续功能。
- 开启 `CAPTCHA_ENABLED` 后，图形验证码答案只存在服务端内存，重启会失效，用户重新获取即可。
- 「第一篇注册用户 UID」为 `1000001`；权限和封禁只通过带令牌认证的 RCON 管理，没有公开授权接口。
