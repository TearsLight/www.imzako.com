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
| `admin_users` | 管理员 UID 名单；存在于此集合的 UID 登录后获得 `ADMIN` 身份 |
| `sequences`   | UID 原子递增计数器，初始值 `1000000`                        |

`users` 对 `uid`、`emailKey`、`usernameKey` 建立唯一索引。邮箱和用户名会生成小写规范化检索键，因此大小写变化不能重复注册。

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
  "gender": "undisclosed",
  "birthday": "2000-01-02",
  "createdAt": "...",
  "updatedAt": "...",
  "enabled": true
}
```

首个注册用户获得 UID `1000001`，后续注册通过 MongoDB 原子 `findAndModify` 每次加一。在并发注册时不会分配相同 UID。发生重复用户名等注册失败时，UID 可能出现空号，这是序列分配的正常行为。

密码不会以明文或可逆密文保存。服务端使用 Spring Security `DelegatingPasswordEncoder`，当前新密码使用带独立随机盐的 bcrypt 哈希，并保留 `{bcrypt}` 前缀，便于以后升级算法。

## 注册

`/register` 提供公开注册表单，前端会校验邮箱、用户名、密码、确认密码和生日，并允许用户选择性别。提交成功后页面进入已登录状态并显示分配到的 UID。

`POST /api/auth/register`

```json
{
  "email": "member@example.com",
  "username": "member",
  "password": "a-long-private-password",
  "gender": "undisclosed",
  "birthday": "2000-01-02"
}
```

- `email`：必填，有效邮箱，最多 254 字符。
- `username`：3–32 字符，支持各语言文字、数字、点、横线和下划线。
- `password`：8–128 字符，服务端不会去除首尾字符。
- `gender`：可选值为 `male`、`female`、`other`、`undisclosed`，默认 `undisclosed`。
- `birthday`：可选，ISO 日期，必须早于当前日期。

成功返回 HTTP 201，并同时建立登录会话。重复用户名或邮箱返回 409，字段校验失败返回 400。响应不会包含 `passwordHash`、内部 Mongo `_id` 或数据库凭据。

## 登录和会话

登录页面调用 `POST /api/auth/login`：

```json
{ "username": "member@example.com", "password": "a-long-private-password" }
```

`username` 可以填写用户名或邮箱。成功响应：

```json
{
  "user": {
    "id": 1000001,
    "username": "member",
    "displayName": "member",
    "email": "member@example.com",
    "gender": "undisclosed",
    "birthday": "2000-01-02",
    "roles": ["USER"]
  }
}
```

其他会话接口：

| 接口                    | 结果                             |
| ----------------------- | -------------------------------- |
| `GET /api/auth/me`      | 返回当前会话用户；未登录返回 401 |
| `POST /api/auth/logout` | 注销并失效当前会话，返回 204     |

Cookie 名、路径、HttpOnly、Secure、SameSite 和会话有效期使用 `.env` 的 `COOKIE_*`、`SESSION_TTL_SECONDS` 配置。当前允许的跨域来源来自逗号分隔的 `ALLOWED_ORIGINS`；认证请求允许携带 Cookie。

## 管理员

公开注册只创建普通用户，不提供公开的管理员注册接口。要授予管理员身份，在确认目标用户 UID 后，由数据库管理员向 `imzako.admin_users` 插入该 UID：

```javascript
use imzako
db.admin_users.insertOne({
  _id: NumberLong("1000001"),
  grantedAt: new Date(),
  grantedBy: "manual"
})
```

该用户下一次登录时响应中的角色为 `["USER", "ADMIN"]`，登录成功页面也会显示“管理员账户”。删除对应 `admin_users` 文档即可撤销身份，账号本身仍保留在 `users` 中。

## 状态码

| 状态 | 含义                 |
| ---- | -------------------- |
| 400  | 请求字段无效         |
| 401  | 未登录或账号密码错误 |
| 409  | 用户名或邮箱已经存在 |
| 503  | MongoDB 暂时不可用   |

当前注册接口尚未加入邮件验证、验证码或频率限制；开放到公网前应结合现有 SMTP 配置补上邮箱验证和注册限流。
