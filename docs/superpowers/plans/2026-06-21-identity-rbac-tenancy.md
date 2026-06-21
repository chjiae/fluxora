# Identity, RBAC, and Tenancy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付真实登录、RBAC、自营租户初始化和平台租户管理全流程。

**Architecture:** Spring Security 使用 HttpOnly JWT Cookie，MyBatis XML 访问 PostgreSQL，Flyway 管理六张核心表。Vue 使用真实 Axios API、Pinia 身份状态和路由守卫；后端是权限与租户状态的最终边界。

**Tech Stack:** Spring Boot 4.1.0、Spring Security、MyBatis、Flyway、PostgreSQL、Vue 3、Pinia、Axios、Playwright。

---

## Claude Code 交接说明

从仓库根目录的 `master` 分支开始实施；当前基础骨架可构建，尚无本轮业务表、业务 SQL、认证或真实租户页面。开始前必须阅读以下文件：

1. `AGENT.md`：用户可见错误的强制安全规范。
2. `docs/superpowers/specs/2026-06-21-identity-rbac-tenancy-design.md`：已经确认的数据、认证和页面边界。
3. 本计划：按 Task 1 至 Task 7 顺序执行，不跳过失败测试、验证和提交。
4. `README.md` 与 `.env.example`：现有本地依赖和启动约定。

实现时保持以下不可变边界：所有数据库 SQL 只能写入 MyBatis XML；全部 Flyway 脚本必须含中文业务注释；`fluxora-gateway` 不得加入任何本轮业务；不实现 Provider、模型、API Key、计费、Redis Pub/Sub/Stream 或 HTTP/SSE 中继；前端不得 Mock 登录、权限或租户 CRUD；用户界面不得显示原始异常、HTTP 状态码、业务错误码或敏感信息。

每个任务完成后运行其列出的命令并执行 `git status --short`、`git diff --check`，只暂存任务相关文件，使用列出的 Conventional Commit。Task 7 是交付门槛：仅当 Maven、前端单测、生产构建和 Playwright 真实流程全部通过，才可宣称本轮完成。

## 文件结构

- `fluxora-common/**`：安全响应、业务错误代码和异常契约。
- `fluxora-platform/src/main/resources/db/migration/**`：中文注释的结构迁移。
- `fluxora-platform/src/main/java/**/{auth,identity,tenant,security}`：认证、RBAC、租户的聚合边界。
- `fluxora-platform/src/main/resources/mapper/**`：全部 MyBatis XML SQL。
- `fluxora-web/src/{services,stores,views,components}/**`：真实 API、身份与租户管理界面。
- `fluxora-web/e2e/**`：真实浏览器流程。

### Task 1: 安全响应、依赖与数据库迁移

**Files:**
- Modify: `pom.xml`, `fluxora-common/pom.xml`, `fluxora-platform/pom.xml`, `fluxora-platform/src/main/resources/application.yml`
- Create: `fluxora-common/src/main/java/io/fluxora/common/error/BusinessErrorCode.java`
- Create: `fluxora-platform/src/main/resources/db/migration/V1__创建用户角色权限租户基础表.sql`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/migration/FlywayMigrationTest.java`

- [ ] 写失败测试：启动 Testcontainers PostgreSQL 并断言 six tables、`tenant_code` 唯一索引和逻辑删除字段存在。
- [ ] 运行 `mvn -pl fluxora-platform -Dtest=FlywayMigrationTest test`，确认迁移类缺失而失败。
- [ ] 增加 Spring Security、JWT、Testcontainers 依赖；开启真实 datasource/Flyway；编写带中文注释的迁移，建立 tenant、user_account、role、permission、user_role、role_permission、索引与约束。
- [ ] 重新运行迁移测试并确认通过。
- [ ] 提交 `feat: 添加用户角色权限基础模型`。

### Task 2: 系统初始化与认证

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/security/**`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/auth/**`
- Create: `fluxora-platform/src/main/resources/mapper/{Identity,Auth}Mapper.xml`
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/identity/**`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/auth/AuthenticationIntegrationTest.java`

- [ ] 写失败测试：首次启动创建单个 `admin`、BCrypt 密码可登录、错误密码返回安全中文提示、重复启动不改密码。
- [ ] 运行 `mvn -pl fluxora-platform -Dtest=AuthenticationIntegrationTest test`，确认认证 API 尚不存在。
- [ ] 实现事务幂等初始化器、JWT Cookie 签发/校验、`/api/auth/login`、`/api/auth/me` 和 Spring Security 权限过滤；XML 实现权限查询；所有异常统一映射为安全响应。
- [ ] 重新运行认证测试并确认通过。
- [ ] 提交 `feat: 初始化平台管理员与认证能力`。

### Task 3: 自营租户初始化与保护规则

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/tenant/{Tenant,SelfOperatedInitialization}*.java`
- Create: `fluxora-platform/src/main/resources/mapper/TenantMapper.xml`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/tenant/SelfOperatedInitializationIntegrationTest.java`

- [ ] 写失败测试：平台管理员可创建 `default` 与首个租户管理员；重复提交安全失败/幂等；自营租户不能停用、删除或过期。
- [ ] 运行目标测试，确认初始化服务不存在。
- [ ] 实现初始化状态查询、事务提交、数据库唯一性处理、TENANT_ADMIN 分配和受保护规则；所有 SQL 写入 XML。
- [ ] 重新运行目标测试并确认通过。
- [ ] 提交 `feat: 添加自营租户初始化流程`。

### Task 4: 平台租户管理 REST API

**Files:**
- Create: `fluxora-platform/src/main/java/io/fluxora/platform/tenant/{TenantController,TenantService,TenantQuery}.java`
- Modify: `fluxora-platform/src/main/resources/mapper/TenantMapper.xml`
- Test: `fluxora-platform/src/test/java/io/fluxora/platform/tenant/TenantManagementIntegrationTest.java`

- [ ] 写失败测试：平台管理员分页、搜索、筛选、新增、编辑、启停、改过期和逻辑删除；租户管理员访问返回友好拒绝。
- [ ] 运行目标测试，确认管理 API 不存在。
- [ ] 实现类型安全 DTO、分页查询、状态动态计算、平台权限保护和所有 CRUD 端点；禁止 Map 拼接响应。
- [ ] 重新运行目标测试并确认通过。
- [ ] 提交 `feat: 添加租户管理后端接口`。

### Task 5: 前端真实认证、权限菜单与初始化向导

**Files:**
- Create: `fluxora-web/src/services/{http,auth,tenant}.ts`, `fluxora-web/src/stores/auth.ts`
- Create: `fluxora-web/src/views/{LoginView,SelfOperatedSetupView}.vue`
- Modify: `fluxora-web/src/router/index.ts`, `fluxora-web/src/views/ConsoleView.vue`, `fluxora-web/src/styles.css`
- Test: `fluxora-web/src/__tests__/auth-router.spec.ts`

- [ ] 写失败测试：未登录跳转登录页，平台管理员在未初始化时跳向导，租户管理员无平台菜单。
- [ ] 运行 `npm run test -- --run --pool=forks --maxWorkers=1`，确认组件/守卫缺失。
- [ ] 实现 Cookie 请求层、统一安全错误映射、身份 store、路由守卫、登录页与两步自营向导；禁止前端硬编码角色和认证结果。
- [ ] 重新运行前端单测与 `npm run build`。
- [ ] 提交 `feat: 添加登录与权限菜单控制`。

### Task 6: 租户管理真实界面

**Files:**
- Create: `fluxora-web/src/views/TenantManagementView.vue`
- Create: `fluxora-web/src/components/tenant/{TenantForm,TenantFilters,TenantActionDialog}.vue`
- Modify: `fluxora-web/src/router/index.ts`, `fluxora-web/src/views/ConsoleView.vue`, `fluxora-web/src/styles.css`
- Test: `fluxora-web/src/__tests__/tenant-management.spec.ts`

- [ ] 写失败测试：筛选重置、受保护租户禁用危险操作、错误提示不暴露技术文本。
- [ ] 运行目标测试，确认租户界面不存在。
- [ ] 实现真实分页、搜索、筛选、详情/编辑、新增、确认操作、响应式表格/列表、加载空态与安全 Toast。
- [ ] 重新运行前端单测和构建。
- [ ] 提交 `feat: 添加租户管理页面`。

### Task 7: 全流程验收与文档

**Files:**
- Modify: `fluxora-web/e2e/layout.spec.ts`, `README.md`, `.env.example`
- Create: `fluxora-web/e2e/identity-tenancy.spec.ts`

- [ ] 写 Playwright 流程：admin 登录、初始化 default、自营管理员登录、普通租户 CRUD、租户管理员不可见/不可访问平台管理，以及三视口检查。
- [ ] 启动本地 PostgreSQL、Redis、platform 与 web；运行 `npx playwright test`，确认新增流程在实现前失败。
- [ ] 补充 README 的初始账号安全说明、启动/手测/API、保护规则及未实现边界。
- [ ] 运行 `mvn clean verify`、`npm run test -- --run --pool=forks --maxWorkers=1`、`npm run build`、`npx playwright test`。
- [ ] 提交 `test: 补充用户租户流程测试` 与 `docs: 补充初始化与测试说明`。

## 自检

- 任务覆盖迁移、RBAC、认证、自营初始化、CRUD、真实前端及浏览器验收。
- 所有数据访问都限定为 MyBatis XML；没有网关或无关业务。
- 用户提示遵循 `AGENT.md`，不向界面暴露状态码或原始异常。
