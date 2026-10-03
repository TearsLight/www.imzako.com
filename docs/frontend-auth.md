# 用户与管理员账号

Spring Boot 已连接 MongoDB，登录页面位于 `/login`，公开注册页面位于 `/register`。账号接口使用 JSON，并通过 HttpOnly Session Cookie 保持登录状态。

## MongoDB 配置

连接信息位于根目录 `.env`：

```dotenv
MONGODB_URI=mongodb://root:<password>@172.16.2.175:27017/imzako?authSource=admin
MONGODB_DATABASE=imzako
MONGODB_USERNAME=root
MONGODB_PASSWORD=<set only in .env>
MONGODB_AUTH_SOURCE=admin
```

Spring Boot 4 通过 `spring.mongodb.uri` 使用该连接。`authSource=admin` 仅表示 MongoDB 的 `root` 凭据在系统 `admin` 数据库中验证；网站账号保存在业务数据库 `imzako`，不写入 MongoDB 的系统数据库。

后端启动时创建并检查以下集合：

| 集合          | 用途                                                        |
| ------------- | ----------------------------------------------------------- |
| `users`       | 网站普通用户、资料和密码哈希                                |
| `admin_users` | UID 权限等级：1 普通用户、2 管理员、3 服主                    |
| `user_bans`   | 临时封禁的到期时间、理由和操作者                              |
| `sequences`   | UID 原子递增计数器，初始值 `1000000`                        |

`users` 只对 `uid`、`emailKey` 建立唯一索引。`usernameKey` 仍然写入，但**没有唯一索引**，因为用户名只是展示标签、允许重复；旧版本遗留的 `username_unique` 索引会在启动时自动删除。邮箱会生成小写规范化检索键，因此大小写变化不能重复注册。`email_verifications.expiresAt` 使用 MongoDB TTL 索引，到期记录由数据库自动清理。

启动时还会把没有 `emailVerified` 字段的存量账号标记为已验证，避免开启邮箱验证后老账号失联。待验证的验证码存放在 `email_verifications` 集合，主键为规范化邮箱，只保存加盐哈希，并带过期时间与重发计数。

用户文档字段：

```json
{
  "_id": "MongoDB ObjectId",
  "uid": 1000001,
  "email": "member@example.com",
  "emailKey": "member@example.com",
  "username": "member",
  "usernameKey": "member",
  "passwordHash": "{bcrypt}...",
  "avatar": "data:image/jpeg;base64,... or absent",
  "createdAt": "...",
  "updatedAt": "...",
  "enabled": true,
  "emailVerified": true,
  "sessionVersion": 0
}
```

首个注册用户获得 UID `1000001`，后续注册通过 MongoDB 原子 `findAndModify` 每次加一。在并发注册时不会分配相同 UID。发生重复邮箱等注册失败时，UID 可能出现空号，这是序列分配的正常行为。启用邮箱验证时，邮件发送失败会删除刚写入的账号和验证记录，用户可以稍后重新注册。

密码不会以明文或可逆密文保存。服务端使用 Spring Security `DelegatingPasswordEncoder`，当前新密码使用带独立随机盐的 bcrypt 哈希，并保留 `{bcrypt}` 前缀，便于以后升级算法。

## 注册

`/register` 提供公开注册表单，前端会校验邮箱、用户名、密码和确认密码。

`POST /api/auth/register`

```json
{
  "email": "member@example.com",
  "username": "member",
  "password": "a-long-private-password"
}
```

- `email`：必填，有效邮箱，最多 254 字符，必须唯一。
- `username`：3–32 字符，支持各语言文字、数字、点、横线和下划线，**允许与他人重复**。
- `password`：8–128 字符，服务端不会去除首尾字符。
- `avatar`：可选，`data:image/...` 形式的图片数据 URI，最长 300000 字符。

行为取决于开关 `app.email-verification.enabled`（本地默认 false，**生产已设为 true**）：

| 开关    | 响应                                                                                 | 会话           |
| ------- | ------------------------------------------------------------------------------------ | -------------- |
| `false` | HTTP 201，`user` 为新建账号，`verificationRequired: false`                           | 立即建立       |
| `true`  | HTTP 201，`user` 为 `null`，`verificationRequired: true`，`maskedEmail` 给出收信地址 | 不建立，等验证 |

邮箱已存在返回 409，字段校验失败返回 400。响应不会包含 `passwordHash`、内部 Mongo `_id` 或数据库凭据。

### 邮箱验证码

开关打开后，注册会调用 `MailService` 向该邮箱发送 6 位验证码，并在 `email_verifications` 中保存其哈希。

| 接口                             | 请求体                                    | 说明                             |
| -------------------------------- | ----------------------------------------- | -------------------------------- |
| `POST /api/auth/register/verify` | `{ "identity": "...", "code": "123456" }` | 校验通过则标记已验证并建立会话   |
| `POST /api/auth/register/resend` | `{ "identity": "..." }`                   | 重发；返回 204，冷却期内返回 400 |

- 验证码默认 600 秒有效（`EMAIL_VERIFICATION_TTL_SECONDS`），一次性使用。
- 连续输错 5 次即作废，需重新获取。
- 重发默认 60 秒冷却（`EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS`），每滚动小时最多 5 封（`EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR`）。
- 公开注册与重发接口同时按来源地址限流，默认分别为每小时 10 次与 20 次；超限返回 `429 RATE_LIMITED`。
- 未知邮箱或不存在的账号也返回 204，避免暴露哪些地址已注册。
- 非 `production` 环境下验证码会写入服务端日志，便于本地联调。

## 登录和会话

登录页面调用 `POST /api/auth/login`：

```json
{ "identity": "member@example.com", "password": "a-long-private-password" }
```

`identity` 可以填写**邮箱或 UID**。用户名因为允许重复，不能用它登录。成功响应：

```json
{
  "user": {
    "id": 1000001,
    "username": "member",
    "displayName": "member",
    "email": "member@example.com",
    "avatar": "data:image/jpeg;base64,...",
    "roles": ["USER"],
    "permissionLevel": 1,
    "bannedUntil": null,
    "banReason": null
  }
}
```

`avatar` 未设置时为 `null`。`permissionLevel` 始终为 1、2 或 3；账号未封禁时 `bannedUntil` 和 `banReason` 为 `null`。权限或封禁经 RCON 修改后，`GET /api/auth/me` 会从数据库刷新公开会话视图，不要求重新登录。

其他会话接口：

| 接口                    | 结果                             |
| ----------------------- | -------------------------------- |
| `GET /api/auth/me`      | 返回当前会话用户；未登录返回 401 |
| `POST /api/auth/logout` | 注销并失效当前会话，返回 204     |

### 登录时的两种 428

`POST /api/auth/login` 在两种情况下不返回用户，而是返回 `428 Precondition Required`：

| `code`               | 触发条件                           | 客户端应做的事                                                    |
| -------------------- | ---------------------------------- | ----------------------------------------------------------------- |
| `CAPTCHA_REQUIRED`   | 开关开启且失败次数达到阈值         | 调用 `GET /api/auth/captcha`，带 `captchaId`/`captchaAnswer` 重试 |
| `EMAIL_NOT_VERIFIED` | 邮箱验证开启且密码正确但邮箱未验证 | 切换到验证码界面，`fields.maskedEmail` 给出收信地址               |

图形验证码由 `CaptchaService` 用 JDK 图像 API 绘制，4 位字符、150×52 PNG，答案只存在服务端内存中：

- 阈值默认 3 次，可用 `LOGIN_CAPTCHA_THRESHOLD` 调整；窗口默认 900 秒（`LOGIN_FAILURE_WINDOW_SECONDS`）。
- 失败次数**同时按邮箱和按来源地址**计数，因此轮换邮箱或轮换 IP 都无法绕过。
- 失败计数默认最多保留 10000 个键（`LOGIN_FAILURE_MAX_TRACKED_KEYS`），图形验证码也有 10000 条上限，避免未受信输入无限占用内存。
- 登录成功会清零两个计数桶。
- 验证码 5 分钟有效、一次性使用；答错返回 `400 INVALID_CAPTCHA`，且该次失败同样计数。
- 开关 `app.captcha.enabled`（**当前为 false**）关闭时完全不校验，`GET /api/auth/captcha` 仍可访问。

## 用户管理

登录后顶部显示「欢迎{用户名}」，点击展开菜单进入 `/account`，页面包含四项管理功能。所有接口都要求有效会话，未登录返回 401。

| 接口                          | 请求体                                               | 说明                                                           |
| ----------------------------- | ---------------------------------------------------- | -------------------------------------------------------------- |
| `PUT /api/auth/username`      | `{ "username": "new-name" }`                         | 3–32 字符；允许重复，不会返回 409                              |
| `PUT /api/auth/avatar`        | `{ "avatar": "data:image/jpeg;base64,..." }`         | 传 `null` 清除头像                                             |
| `PUT /api/auth/email`         | `{ "currentPassword": "...", "email": "..." }`       | 当前密码错误返回 400；验证开启时先向新邮箱发码，此时原邮箱不变 |
| `POST /api/auth/email/verify` | `{ "code": "123456" }`                               | 验证新邮箱并完成改绑；已占用返回 409                           |
| `PUT /api/auth/password`      | `{ "currentPassword": "...", "newPassword": "..." }` | 成功后递增会话版本、使**所有设备的会话**失效并返回 204         |

用户名与头像接口返回更新后的用户对象，服务端会同步刷新会话中的用户快照。邮箱验证开启时，首次改绑请求返回 `verificationRequired: true` 和打码地址，完成验证后才返回新的用户对象。密码接口返回 204，前端会清除本地登录状态；其他已登录设备会在下次请求时因会话版本不匹配而被注销。

头像由前端处理：读取用户选择的文件，用 `canvas` 居中裁剪为 256×256 后按 quality 0.85 起逐级压缩为 JPEG data URI，源文件超过 5 MB 时直接拒绝。服务端只校验前缀必须是 `data:image/` 且长度不超过 300000 字符。

Cookie 名、路径、HttpOnly、Secure、SameSite 和会话有效期使用 `.env` 的 `COOKIE_*`、`SESSION_TTL_SECONDS` 配置。当前允许的跨域来源来自逗号分隔的 `ALLOWED_ORIGINS`；认证请求允许携带 Cookie，并允许 `PUT` 方法。

## 权限与封禁

公开注册只创建等级 1 普通用户。权限和封禁通过认证后的 8090 RCON 管理：

```text
op 1000001 2
op 1000001 3
ban 1000002 24 "刷屏"
pardon 1000002
user 1000002
```

| 等级 | 角色                         | 当前能力                                      |
| ---- | ---------------------------- | --------------------------------------------- |
| 1    | `USER`                       | 普通登录用户                                  |
| 2    | `USER`、`ADMIN`              | 管理员身份；预留给后续管理功能                |
| 3    | `USER`、`ADMIN`、`OWNER`     | 读取博客管理数据，并新增、修改和删除博客      |

权限会写入兼容旧版本的 `admin_users` 集合；没有记录时按等级 1，旧版没有 `level` 字段的记录按等级 2。封禁写入 `user_bans`，用户响应会额外带 `BANNED` 角色、到期时间和理由。封禁期间公开内容与等级 3 博客管理列表仍可读，但博客新增、修改和删除会返回 403；未来聊天室、留言板和评论区实现写接口时必须调用同一 `requireWritablePermission` 检查。MongoDB TTL 索引会最终清理过期记录，应用本身也按当前时间判断封禁是否有效，因此不依赖 TTL 清理的即时性。

RCON 是独立的受令牌保护管理面，不是 HTTP API。完整命令、认证方式和 Docker 端口绑定见 [RCON 管理文档](rcon.md)。

## 状态码

| 状态 | 含义                                   |
| ---- | -------------------------------------- |
| 400  | 请求字段无效                           |
| 401  | 未登录或账号密码错误                   |
| 403  | 权限不足，或封禁期间尝试受保护的写操作 |
| 409  | 邮箱已经存在                           |
| 429  | 公开注册、重发或验证码图片请求过于频繁 |
| 503  | MongoDB 暂时不可用                     |
