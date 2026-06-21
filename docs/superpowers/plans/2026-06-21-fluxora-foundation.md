# Fluxora Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可构建、可独立启动且边界清晰的 Fluxora 前后端基础骨架与静态界面。

**Architecture:** Maven Parent 聚合纯公共 JAR、Spring Boot 控制面与原生 Vert.x 数据面；前端为独立 Vue 3 工程，公开区域和控制台拥有不同布局。Docker Compose 仅运行 Fluxora 三个应用，通过环境变量连接已有的本地 PostgreSQL 与 Redis。

**Tech Stack:** JDK 25、Maven、Spring Boot 4.1.0、MyBatis Starter 4.0.1、Flyway、PostgreSQL、Redis、Vert.x 5.1.2、Vue 3、Vite、TypeScript、Vue Router、Pinia、Axios、lucide-vue-next。

---

## 文件职责

- `pom.xml`：reactor、版本属性、BOM 与插件管理。
- `fluxora-common/**`：无服务依赖的通用响应和异常。
- `fluxora-platform/**`：Spring Boot 启动类、运行配置、健康端点依赖。
- `fluxora-gateway/**`：Vert.x 启动类与最小 HTTP 健康端点。
- `fluxora-web/**`：路由、主题、布局、静态页面与前端构建配置。
- `docker-compose.yml`：仅编排 platform、gateway、web。
- `.env.example`：安全的本地连接变量示例。
- `README.md`：边界、选型、启动与验证说明。

### Task 1: 验证依赖版本并初始化仓库元文件

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `pom.xml`
- Create: `README.md`

- [ ] **Step 1: 查询官方文档并记录可用配置方式**

使用 Context7 分别查询 Spring Boot 4.1.0、MyBatis Spring Boot Starter 4.0.1、Vert.x 5.1.2、Vue 3 与 Vite 的官方资料，确认 Maven 坐标、BOM 用法、Spring 配置名、Vert.x 5 HTTP Server API 与 Vite 生产构建命令。

- [ ] **Step 2: 创建根 POM 和安全忽略规则**

根 POM 使用 `packaging` 为 `pom`，声明 `fluxora-common`、`fluxora-platform`、`fluxora-gateway` 三个模块；仅在 dependencyManagement 中统一版本。`.gitignore` 忽略 Maven `target/`、前端 `node_modules/`、前端 `dist/`、`.env`、IDE 文件和 `.superpowers/`。

- [ ] **Step 3: 验证空 reactor 可以解析**

Run: `mvn -q validate`

Expected: Maven 成功识别 Parent POM；尚无模块编译产物。

- [ ] **Step 4: 提交仓库与 Parent 基础**

```powershell
git add pom.xml .gitignore .env.example README.md
git commit -m "build: 初始化单仓库与 Maven 父工程"
```

### Task 2: 添加 fluxora-common 最小公共模块

**Files:**
- Create: `fluxora-common/pom.xml`
- Create: `fluxora-common/src/main/java/io/fluxora/common/api/ApiResponse.java`
- Create: `fluxora-common/src/main/java/io/fluxora/common/exception/FluxoraException.java`
- Test: `fluxora-common/src/test/java/io/fluxora/common/api/ApiResponseTest.java`

- [ ] **Step 1: 写出公共响应创建行为的失败测试**

测试 `ApiResponse.success("ok")` 返回 `success=true` 与数据 `ok`，并测试 `ApiResponse.failure("bad")` 返回 `success=false` 与消息 `bad`。

- [ ] **Step 2: 运行测试并确认因类不存在失败**

Run: `mvn -pl fluxora-common test`

Expected: 编译失败，提示 `ApiResponse` 不存在。

- [ ] **Step 3: 实现最小公共响应与基础异常**

`ApiResponse<T>` 只声明成功标记、数据与消息以及两个静态工厂；`FluxoraException` 继承 `RuntimeException`。不创建任何 Provider、模型、路由或 API Key 类型。

- [ ] **Step 4: 运行公共模块测试**

Run: `mvn -pl fluxora-common test`

Expected: PASS。

- [ ] **Step 5: 提交公共模块**

```powershell
git add fluxora-common
git commit -m "feat: 添加公共依赖模块"
```

### Task 3: 添加 platform 可启动骨架

**Files:**
- Create: `fluxora-platform/pom.xml`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/FluxoraPlatformApplication.java`
- Create: `fluxora-platform/src/main/resources/application.yml`
- Create: `fluxora-platform/src/test/java/io/fluxora/platform/FluxoraPlatformApplicationTest.java`

- [ ] **Step 1: 写失败的 Spring 上下文加载测试**

创建 `@SpringBootTest`，断言应用上下文可加载；测试配置仅设置随机端口，不连实际数据库或 Redis。

- [ ] **Step 2: 运行测试并确认启动类不存在失败**

Run: `mvn -pl fluxora-platform test`

Expected: 编译失败，提示应用启动类不存在。

- [ ] **Step 3: 实现平台启动类和配置**

依赖 common、Spring Web、Actuator、MyBatis、Flyway、PostgreSQL 和 Redis。配置通过环境变量暴露连接参数，但禁用本阶段不应运行的数据库迁移与数据源初始化，使未接入基础组件时仍可启动并由 Actuator 提供 `/actuator/health`。

- [ ] **Step 4: 运行平台测试和健康检查**

Run: `mvn -pl fluxora-platform test`

Expected: PASS。

Run: `mvn -pl fluxora-platform spring-boot:run`

Expected: `GET http://localhost:8080/actuator/health` 返回 HTTP 200 与 `UP`。

- [ ] **Step 5: 提交平台服务**

```powershell
git add fluxora-platform pom.xml
git commit -m "feat: 添加平台服务基础骨架"
```

### Task 4: 添加 gateway 可启动骨架

**Files:**
- Create: `fluxora-gateway/pom.xml`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/FluxoraGatewayApplication.java`
- Create: `fluxora-gateway/src/main/java/io/fluxora/gateway/HealthHttpServer.java`
- Create: `fluxora-gateway/src/test/java/io/fluxora/gateway/HealthHttpServerTest.java`

- [ ] **Step 1: 写出健康端点的失败集成测试**

启动临时 Vert.x HTTP server，向 `/health` 发起请求，断言 HTTP 200 与响应体中的 `status` 为 `UP`。

- [ ] **Step 2: 运行测试并确认因为服务器类不存在而失败**

Run: `mvn -pl fluxora-gateway test`

Expected: 编译失败，提示 `HealthHttpServer` 不存在。

- [ ] **Step 3: 实现原生 Vert.x 启动与健康路由**

模块仅依赖 common、Vert.x 和 Redis 客户端。启动类从 `GATEWAY_PORT` 读取端口（默认 8081），`HealthHttpServer` 只注册 `GET /health`，返回 JSON `{"status":"UP"}`。不加入 Spring、数据库、MyBatis、Flyway、JPA 或数据库驱动。

- [ ] **Step 4: 运行网关测试和端点检查**

Run: `mvn -pl fluxora-gateway test`

Expected: PASS。

Run: `mvn -pl fluxora-gateway exec:java`

Expected: `GET http://localhost:8081/health` 返回 HTTP 200 与 `{"status":"UP"}`。

- [ ] **Step 5: 提交网关服务**

```powershell
git add fluxora-gateway pom.xml
git commit -m "feat: 添加网关服务基础骨架"
```

### Task 5: 初始化 Vue 前端基础设施

**Files:**
- Create: `fluxora-web/package.json`
- Create: `fluxora-web/vite.config.ts`
- Create: `fluxora-web/tsconfig.json`
- Create: `fluxora-web/src/main.ts`
- Create: `fluxora-web/src/App.vue`
- Create: `fluxora-web/src/router/index.ts`
- Create: `fluxora-web/src/stores/app.ts`
- Create: `fluxora-web/src/services/http.ts`
- Create: `fluxora-web/src/styles/tokens.css`
- Create: `fluxora-web/src/styles/base.css`

- [ ] **Step 1: 写失败的路由渲染测试**

使用 Vitest 与 Vue Test Utils 渲染应用，断言访问 `/` 时出现 `Fluxora`；访问 `/docs` 时出现 `文档`。

- [ ] **Step 2: 运行测试并确认由于应用入口不存在失败**

Run: `npm run test -- --run`

Expected: FAIL，提示 Vite/Vue 入口或组件不存在。

- [ ] **Step 3: 建立基础依赖、主题和请求层**

安装 Vue、Vue Router、Pinia、Axios、lucide-vue-next、Vitest 和 Vue Test Utils。主题 store 在 `light`/`dark` 间切换并持久化；tokens 定义石墨、骨白、灰阶与电蓝绿强调色；Axios 实例只配置超时、JSON header 与可替换 base URL，不接真实业务 API。

- [ ] **Step 4: 运行单元测试和生产构建**

Run: `npm run test -- --run; npm run build`

Expected: 测试通过且 Vite 生成 `dist/`。

- [ ] **Step 5: 提交前端基础**

```powershell
git add fluxora-web
git commit -m "feat: 初始化 Vue 前端工程"
```

### Task 6: 实现公开官网与文档静态骨架

**Files:**
- Create: `fluxora-web/src/layouts/PublicLayout.vue`
- Create: `fluxora-web/src/views/HomeView.vue`
- Create: `fluxora-web/src/views/DocsView.vue`
- Create: `fluxora-web/src/components/public/PublicHeader.vue`
- Create: `fluxora-web/src/components/public/SiteFooter.vue`
- Create: `fluxora-web/src/components/common/AsyncState.vue`
- Modify: `fluxora-web/src/router/index.ts`
- Test: `fluxora-web/src/views/HomeView.test.ts`
- Test: `fluxora-web/src/views/DocsView.test.ts`

- [ ] **Step 1: 写两个失败的页面内容测试**

首页测试断言存在“API 中转”“流式接口”“常见问题”；文档页测试断言存在目录、“OpenAI 协议接入”与当前章节标记。

- [ ] **Step 2: 运行测试并确认页面组件不存在失败**

Run: `npm run test -- --run`

Expected: FAIL，提示 `HomeView` 或 `DocsView` 不存在。

- [ ] **Step 3: 实现公开布局与静态页面**

Header 提供产品介绍、产品优势、文档、FAQ 和进入控制台入口；首页按定位、能力、优势、接入引导、FAQ、页脚顺序组织；文档页含可折叠移动目录、当前章节高亮与 Mock 内容。所有公开页面只使用顶部导航，不使用侧边栏。

- [ ] **Step 4: 运行前端测试与构建**

Run: `npm run test -- --run; npm run build`

Expected: PASS，且 `dist/` 成功产出。

- [ ] **Step 5: 提交公开页面**

```powershell
git add fluxora-web
git commit -m "feat: 添加官网与文档页面骨架"
```

### Task 7: 实现控制台布局、主题和响应式状态

**Files:**
- Create: `fluxora-web/src/layouts/ConsoleLayout.vue`
- Create: `fluxora-web/src/components/console/ConsoleSidebar.vue`
- Create: `fluxora-web/src/components/console/ConsoleTopbar.vue`
- Create: `fluxora-web/src/views/ConsoleOverviewView.vue`
- Create: `fluxora-web/src/views/ConsolePlaceholderView.vue`
- Modify: `fluxora-web/src/router/index.ts`
- Test: `fluxora-web/src/layouts/ConsoleLayout.test.ts`

- [ ] **Step 1: 写控制台布局失败测试**

断言桌面布局显示“概览”“项目管理”“平台管理”和主题切换按钮；通过点击折叠按钮断言侧边栏折叠状态改变。

- [ ] **Step 2: 运行测试并确认控制台布局不存在失败**

Run: `npm run test -- --run`

Expected: FAIL，提示 `ConsoleLayout` 不存在。

- [ ] **Step 3: 实现控制台静态布局与状态**

侧边栏包含用户、租户和平台未来可用的所有菜单占位，但不做权限判断。顶栏提供 Mock 用户入口、亮暗主题切换与移动端菜单。概览页展示 Mock 摘要与克制的空、加载、错误状态。桌面为侧栏布局，平板收窄，移动端抽屉式导航。

- [ ] **Step 4: 运行前端测试与构建**

Run: `npm run test -- --run; npm run build`

Expected: PASS，且 production build 成功。

- [ ] **Step 5: 提交控制台布局**

```powershell
git add fluxora-web
git commit -m "feat: 添加控制台布局与主题系统"
```

### Task 8: 添加容器编排、文档和端到端视觉验证

**Files:**
- Create: `docker-compose.yml`
- Create: `fluxora-platform/Dockerfile`
- Create: `fluxora-gateway/Dockerfile`
- Create: `fluxora-web/Dockerfile`
- Create: `fluxora-web/nginx.conf`
- Modify: `.env.example`
- Modify: `README.md`
- Create: `fluxora-web/playwright.config.ts`
- Create: `fluxora-web/e2e/layout.spec.ts`

- [ ] **Step 1: 写跨视口页面可达性失败测试**

Playwright 项目定义 desktop（1440×900）、tablet（768×1024）、mobile（390×844）。每种视口访问 `/`、`/docs`、`/console`；断言关键导航可见，页面 `scrollWidth` 不超过 `innerWidth`，并为三个路由保存截图。

- [ ] **Step 2: 运行检查并确认在未实现的视觉页面上失败**

Run: `npx playwright test`

Expected: 在页面或本地服务缺失时失败，失败原因明确。

- [ ] **Step 3: 添加只编排项目服务的 Docker 文件与 Compose**

Platform 和 gateway 使用多阶段 Maven 镜像构建，web 使用 Vite 构建并由 Nginx 静态服务。Compose 只定义 `platform`、`gateway`、`web`，通过 `POSTGRES_HOST` 与 `REDIS_HOST` 等变量连接 `host.docker.internal`，不声明 PostgreSQL、Redis 服务、卷或初始化脚本。

- [ ] **Step 4: 记录完整边界与启动说明**

README 说明模块职责、前端选型理由、主题、页面路由、已有本地基础组件的连接方式、Maven/前端/Compose 启动命令、健康检查 URL 与本阶段刻意未实现的功能。

- [ ] **Step 5: 运行完整验证**

Run: `mvn clean verify; npm ci; npm run test -- --run; npm run build; npx playwright install; npx playwright test`

Expected: 后端构建、前端单元测试、生产构建和九个（3 路由 × 3 视口）视觉检查全部通过。

- [ ] **Step 6: 提交交付文档与验证**

```powershell
git add docker-compose.yml fluxora-platform/Dockerfile fluxora-gateway/Dockerfile fluxora-web/Dockerfile fluxora-web/nginx.conf fluxora-web/playwright.config.ts fluxora-web/e2e/layout.spec.ts .env.example README.md
git commit -m "docs: 补充本地开发说明"
```

## 计划自检

- 覆盖：Parent、三个后端模块、Vue 基础、公开页、文档、控制台、主题、响应式、Compose、README、构建与 Playwright 均有独立任务。
- 边界：没有任务创建业务表、业务 SQL、鉴权、中继、缓存、消息或计费实现。
- 一致性：平台端点为 `/actuator/health`，网关端点为 `/health`；Compose 不定义基础组件。
- 测试：每项行为代码先有可观察的失败测试，随后才实现最小代码。
