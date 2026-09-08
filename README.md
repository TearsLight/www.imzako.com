# www.imzako.com

现有 Vue 3 + TypeScript + Vite 前端，以及 Java 21 + Spring Boot 4.1.1 后端。
Vue Router 在 `/` 展示 HomePage 站点导航，在 `/blog` 展示暂未发布文章的博客页，在 `/login` 展示用户登录页；Spring Boot 提供页面入口和 API。
站点导航配置位于 `src/config/sites.ts`。登录前端接口与后端响应约定见 [登录接入说明](docs/frontend-auth.md)。
前端采用响应式布局、页面进出过渡、分层入场和导航滑动动画，并支持系统的减少动态效果设置。

## 开发环境

- JDK 21。本机安装位置：`E:\Environment\Java\jdk-21.0.11`，IDEA 中 SDK 名称为 `21`。
- Node.js：符合 `package.json` 的 `^22.18.0 || >=24.12.0`，本机为 `24.18.0`。
- Maven：项目包含 Maven Wrapper（3.9.16），无需另外安装全局 Maven。
- IDEA 自带的 Maven 3.9.16 也可直接使用；本项目运行配置采用普通 Maven 配置。

首次打开项目先运行 `npm ci` 安装前端依赖。已有可用 `node_modules` 时无需重复安装。
首次运行 Maven Wrapper 会下载 Maven，首次构建会下载 Java 依赖，需要访问 Maven Central。
本机连接 Maven Central 官方地址时出现 TLS 握手失败，因此项目使用阿里云 Maven Central 镜像 `https://maven.aliyun.com/repository/central`，不关闭证书验证。

## 在 IDEA 中运行

1. 打开项目根目录或根目录的 `pom.xml`。若原工程已打开而 Maven 面板没有项目，右键 `pom.xml` → **Add as Maven Project**。
2. 在 **File → Project Structure → Project SDK** 确认选择 `21`。当前工程已经设置为 JDK 21。
3. 在 **Settings → Build, Execution, Deployment → Build Tools → Maven** 中可使用 **Bundled (Maven 3)** 或 **Maven Wrapper**；Runner 的 JRE 选择 **Project JDK**。
4. 等待 Maven 同步完成。运行下拉框选择 **Full Stack**，同时启动后端和 Vite。也可分别运行 **Backend** 或 **Frontend**。
5. 开发时打开 `http://localhost:5173/` 或 `http://localhost:5173/blog`，Vue 文件修改后由 Vite 热更新。

`.run` 中保存了四个共享配置：

| 配置 | 用途 |
| --- | --- |
| Full Stack | 同时启动 Backend 和 Frontend |
| Backend | 构建当前前端并启动 Spring Boot，端口 8080 |
| Frontend | 启动 Vite，端口 5173，并将 `/api` 代理到后端 |
| Verify | 构建前端、运行后端集成测试并打包 JAR |

修改 Java 代码后在 IDEA 中执行 **Build Project**（Windows 默认 `Ctrl+F9`），DevTools 检测到编译结果变化后会重启应用。
直接访问 8080 看到的是本次后端启动或构建时的前端产物；开发前端请使用 5173。

## 命令行运行（Windows PowerShell）

在项目根目录打开两个终端。

后端：

```powershell
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
npm run dev
```

macOS / Linux 使用 `./mvnw` 替代 `.\mvnw.cmd`（必要时先 `chmod +x mvnw`）。

## 路由

| 地址 | 结果 |
| --- | --- |
| `GET /` | 返回 Vue 页面入口，显示 HomePage 站点导航 |
| `GET /login`、`GET /login/` | 返回 Vue 页面入口，显示用户登录页（认证后端待接入） |
| `GET /blog`、`GET /blog/` | 返回 Vue 页面入口，显示博客空状态，可直接打开或刷新 |
| `GET /about`、`GET /about/` | 兼容旧地址，由前端重定向到首页 |
| `GET /api/health` | JSON：`{"status":"UP","application":"www.imzako.com"}` |
| `/assets/*` | 前端构建出的 JS、CSS 等静态资源 |

后端只为已知页面提供 Vue 入口；不存在的 API、资源和页面返回 404。
Vite 开发服务器将 `/api` 请求转发到 `http://127.0.0.1:8080`，页面可以直接使用：

```ts
const response = await fetch('/api/health')
if (!response.ok) throw new Error(`请求失败：${response.status}`)
const health = await response.json()
```

这里的健康接口仅表示 Web 应用已运行；当前两个页面没有数据库需求。

## 构建和验证

```powershell
.\mvnw.cmd clean verify
npm run test:unit -- --run
java -jar target\imzako-0.0.1-SNAPSHOT.jar
```

Maven 在 `generate-resources` 阶段执行 `npm run build`（含 TypeScript 检查），再把 `dist` 复制到 JAR 内的静态资源目录。
完成后只需 JDK 即可运行 JAR；运行 JAR 时不需要 Node、Vite 或外部 Tomcat。
集成测试使用随机端口和真实 HTTP 请求，检查页面刷新、JS 资源、健康 API 以及 404 行为。

## 端口调整

后端默认 8080，可使用环境变量 `SERVER_PORT` 或启动参数 `--server.port=8081` 调整：

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

如果修改了后端端口，在前端终端同步设置代理地址：

```powershell
$env:BACKEND_URL = 'http://127.0.0.1:8081'
npm run dev
```

这两个变量由启动进程读取；Spring Boot 不会自动读取前端项目的 `.env`。
Vite 固定使用 5173，端口占用时会明确报错，可停止旧实例后重试。

## 目录

```text
src/views/                         现有 Vue 页面
src/router/                        现有 Vue 路由
src/main/java/com/imzako/           Spring Boot 启动类与控制器
src/main/resources/                后端配置
src/test/java/com/imzako/           HTTP 集成测试
.run/                              IDEA 共享运行配置
.mvn/wrapper/                      Maven Wrapper 配置
pom.xml                            Java 依赖与前后端联合构建
vite.config.ts                     Vite 与 API 代理
```

参考：[Spring Boot Web 文档](https://docs.spring.io/spring-boot/reference/web/servlet.html)、[IDEA Maven 文档](https://www.jetbrains.com/help/idea/maven-support.html)、[Vite 开发代理](https://vite.dev/config/server-options.html#server-proxy)。
