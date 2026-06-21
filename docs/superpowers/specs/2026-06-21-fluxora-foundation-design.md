# Fluxora 基础骨架设计

## 目标与范围

本阶段建立 Fluxora 多租户 API 中转平台的单仓库前后端基础骨架。交付可构建、可独立启动的控制面和数据面最小服务，以及具备官网、文档和控制台静态骨架的 Vue 前端。所有页面使用简体中文与本地 Mock 数据。

本阶段刻意不实现领域业务、真实数据、数据库表、SQL、鉴权、请求中继、流式转发、Redis 缓存/消息逻辑或计费逻辑。

## 仓库结构

```text
.
├── pom.xml
├── fluxora-common/
├── fluxora-platform/
├── fluxora-gateway/
├── fluxora-web/
├── docker-compose.yml
├── .env.example
├── README.md
└── docs/superpowers/
```

根 POM 为 `pom` 打包方式的 Maven Parent，统一管理 JDK 25、Spring Boot 4.1.0、MyBatis Spring Boot Starter 4.0.1、Vert.x 5.1.2 与构建插件版本。三个后端模块均位于同一 reactor 内。

## 后端边界

### fluxora-common

仅产出可被两个服务模块使用的 JAR。初始内容仅包含通用响应结构和基础异常，且不依赖平台、网关或任何业务领域模型。

### fluxora-platform

Spring Boot 4.1.0 控制面服务，依赖 `fluxora-common`。引入 PostgreSQL、Redis、Flyway 和 MyBatis 的基础依赖及本地开发连接配置；后续 SQL 只能放入 MyBatis XML。当前不创建迁移脚本、表、Mapper、Repository、SQL 或 CRUD 接口。通过 Actuator 暴露最小健康检查。

### fluxora-gateway

独立 Vert.x 5.1.2 数据面进程，依赖 `fluxora-common` 与 Redis 客户端，仅提供 HTTP `GET /health`。它不依赖 Spring、PostgreSQL、MyBatis、Flyway、JPA 或任何数据库驱动。后续 Redis 本地缓存、Pub/Sub 与 Stream 均不在本阶段实现。

## 前端架构与视觉系统

前端目录固定为 `fluxora-web`，使用 Vue 3、Vite、TypeScript、Vue Router、Pinia、Axios 与 lucide-vue-next。请求层只做可替换的基础封装，不发起真实业务请求；Mock 数据就近存放于前端。

路由分为公开区域（`/` 与 `/docs`）和控制台区域（`/console/...`）。公开区域使用顶部 Header 和锚点导航；控制台使用独立侧边栏、顶部操作区和可折叠响应式布局。两者共享主题令牌、空状态、加载状态与错误状态。

视觉采用石墨黑、骨白和中性灰作为主基底，单一低饱和电蓝绿色作为功能强调。官网英雄区域可使用一处极轻的网格或光晕渐变；控制台不使用渐变。通过排版、边界、间距与对比建立层级，避免过度圆角、玻璃拟态、霓虹、卡片堆叠及模板化 SaaS 视觉。

字体采用系统优先栈：`Inter`、`PingFang SC`、`Microsoft YaHei` 与无衬线回退，不依赖外部运行时字体请求。默认语言为简体中文。

## 本地运行与部署边界

项目提供 `docker-compose.yml`，只编排 Fluxora 自身的 platform、gateway 和 web 服务。它不部署 PostgreSQL 或 Redis；两者由开发者本地已运行的 Docker 基础组件提供。Compose 默认通过 Docker Desktop 的 `host.docker.internal` 访问它们，并允许用环境变量覆盖主机、端口与凭据。

`.env.example` 只包含安全的本地开发示例，不含真实凭据。服务启动不因本阶段尚未使用 Redis 或 PostgreSQL 而主动访问或初始化它们。

## 验证与交付

后端将执行 Maven reactor 构建，并对 platform 与 gateway 的健康端点进行启动验证。前端将执行生产构建，并使用 Playwright 在桌面、平板和移动视口检查首页、文档页、控制台布局及无横向溢出。README 记录架构、依赖选型、启动步骤、页面结构和本阶段边界。

提交使用 Conventional Commits 中文描述并保持小粒度。每次提交前检查 `git status`、`git diff`，仅暂存明确文件，并先运行对应验证。

## 自检

- 不包含待定项或未来功能的伪实现。
- platform 与 gateway 的数据库职责严格隔离。
- Compose 明确仅编排项目服务，不承担基础组件部署。
- 前端公开区与控制台导航模型独立。
- 每项本轮要求均有对应结构、文档或验证路径。
