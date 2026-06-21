# Fluxora

Fluxora 是一个多租户 API 中转平台的基础骨架：控制面负责未来的配置与运营能力，数据面负责未来的高性能请求中继。本轮只交付可构建、可启动的基础服务与静态前端，不包含真实业务。

## Modules

- `fluxora-common`：无服务依赖的通用响应与异常。
- `fluxora-platform`：Spring Boot 4.1.0 控制面，提供 `/actuator/health`；包含 PostgreSQL、Redis、Flyway、MyBatis 的依赖和连接约定，但没有表、迁移或 SQL。
- `fluxora-gateway`：Vert.x 5.1.2 数据面，提供 `/health`；不依赖 Spring 或数据库组件。
- `fluxora-web`：Vue 3 + Vite 静态官网、文档和控制台骨架。

## Prerequisites

- JDK 25、Maven 3.9+、Node.js 22+
- 已在本地 Docker 中运行的 PostgreSQL 与 Redis（项目不会部署它们）。

## 启动

```powershell
mvn clean verify
```

```powershell
# 平台服务：http://localhost:8080/actuator/health
mvn -pl fluxora-platform -am spring-boot:run

# 网关服务：http://localhost:8081/health
mvn -pl fluxora-gateway -am exec:java -Dexec.mainClass=io.fluxora.gateway.FluxoraGatewayApplication

cd fluxora-web
npm ci
npm run dev
```

复制 `.env.example` 为 `.env` 后，可覆盖 PostgreSQL、Redis、端口等本地参数。`docker compose up --build` 只启动 platform、gateway、web；基础组件继续使用现有本地 Docker 实例，默认经 `host.docker.internal` 访问。

## 前端选型

Vue 3 + Vite + TypeScript 保持轻量工程基础；Vue Router 与 Pinia 分别处理路由和界面状态；Axios 是可替换的请求层；lucide-vue-next 提供一致且克制的图标。视觉以石墨黑、骨白、灰阶为基础，电蓝绿仅作强调，避免模板化后台和过度装饰。

公开区使用顶部 Header（`/`、`/docs`），控制台使用独立左侧导航（`/console`）。本轮所有页面均为 Mock 数据。

## 当前阶段边界

未实现业务表、Flyway 迁移、MyBatis Mapper/XML SQL、Provider/Model/Route/API Key、登录鉴权、Redis 缓存或消息、计费、日志、真实 HTTP/SSE 中继、协议适配及权限控制。后续所有面向用户的错误提示必须遵循根目录 `AGENT.md`：不展示技术错误、状态码或敏感内部信息。
