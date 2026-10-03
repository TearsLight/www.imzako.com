# 更新日志

本项目遵循语义化版本（Semantic Versioning）。

## [0.1.0] - 2026-10-04

首个可部署版本。运行时只需一个可执行 JAR 与同目录的 `.env`，前端产物在打包时自动构建并嵌入 JAR。

### 新增

- **8090 RCON 管理端口**：带令牌认证的 UTF-8 行协议，提供 `imzako new`、`op`、`ban`、`pardon`、`user` 等管理命令。
- **三级权限**：1 普通用户 / 2 管理员 / 3 服主，持久化到 MongoDB `admin_users`。
- **用户封禁**：`ban UID 小时数 "理由"`，封禁用户仅保留只读能力，记录到期由 MongoDB TTL 索引自动清理。
- **等级 3 博客管理**：网页端新增、修改、删除博客（`/api/blog/manage/**` 与前端管理面板）。
- **博客搜索**：`/blog` 支持按标题、摘要、分类即时过滤。
- **BillionMail 邮件**：注册邮箱验证码经 BillionMail 发送 API 投递，SMTP 仅作可选回退。
- **邮箱验证 / 图形验证码 / 公开接口限流**：三项安全门禁均已实现（默认按生产推荐值配置）。

### 变更

- 移除 `scripts/new-post.mjs`（原 npm 建文脚本），建文改由 JAR 内置 RCON 完成。
- `.env` 改为**强制读取**（`spring.config.import=file:.env[.properties]`，缺失即启动失败）。
- 门禁开关改为大写环境变量：`EMAIL_VERIFICATION_ENABLED`、`CAPTCHA_ENABLED`。
- 邮件发送改为只调用 BillionMail API，`MailService` 中 `JavaMailSender` 改为 `ObjectProvider` 以便无 SMTP 时仍可启动。
- 配置注释全面汉化并分类。

### 文档

- 新增 `.env.example` 脱敏配置模板（敏感值以 `CHANGE_ME` 占位）。
- 新增 `.env 配置设计规范`（`docs/env-spec.md`，必填项以 `*` + 加粗标记）。
- 新增 RCON 管理文档（`docs/rcon.md`）。
- 同步更新 README、`docs/deployment.md`、`docs/frontend-auth.md`、`docs/frontend-blog.md`。
