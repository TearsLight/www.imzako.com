# 登录前端接入约定

页面：`/login`。Spring Boot 已为 `/login`、`/login/` 提供前端页面入口。

## 登录接口

统一调用位于 `src/services/auth.ts` 的 `login(credentials, signal?)`。目前仅有前端，未实现服务端认证；现有后端会返回 404，页面显示“登录服务暂未开放”，不会模拟成功。

请求：`POST /api/auth/login`，`Content-Type: application/json`，`credentials: include`。

```json
{ "username": "example", "password": "user-provided-password" }
```

成功响应：HTTP 200。

```json
{ "user": { "id": "1", "username": "example", "displayName": "显示名称" } }
```

`id` 支持字符串或数字，`username` 必填，`displayName` 可选。后端应创建真实会话并通过 HttpOnly Cookie 维护登录态，在后端实现会话校验、CSRF 防护和退出登录。当前页面仅确认本次登录结果，不实现跨页面用户状态、会话恢复或站点间单点登录。密码仅保留在表单内存中，成功或离开页面后清空，不写入浏览器存储。

前端处理：400 输入错误；401 凭据无效；403 请求被拒；404/501 服务未开放；429 请求过多；其他非成功响应为服务异常。网络错误、10 秒超时以及响应格式错误会展示可重试提示。提交期间禁止重复提交，离开页面取消请求。

## 站点导航

编辑 `src/config/sites.ts` 管理导航地址和卡片内容。Git 为 `https://git.imzako.com`，博客按当前要求使用 `http://localhost:8080/blog`。上线时可统一替换博客地址为 `https://www.imzako.com/blog`。

向 `sites` 数组追加配置即可添加站点；没有 `href` 的条目显示为“敬请期待”，不会生成不可用的链接。现有图标类型为 `git`、`blog`、`plus`。
