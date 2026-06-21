# Fluxora

Fluxora 是一个多租户 API 中转平台：控制面负责配置与运营，数据面负责高性能请求中继。当前阶段已实现用户体系、RBAC 权限、多租户与平台管理。

## Modules

- `fluxora-common`：通用响应、错误编码、异常契约。
- `fluxora-platform`：Spring Boot 4.1.0 控制面，包含认证、RBAC、租户管理 REST API；PostgreSQL + Flyway + MyBatis。
- `fluxora-gateway`：Vert.x 5.1.2 数据面，提供 `/health`；不依赖 Spring 或数据库组件。
- `fluxora-web`：Vue 3 + Vite 控制台与官网；Pinia 状态管理、Axios + Cookie 认证、路由守卫。

## Prerequisites

- JDK 25、Maven 3.9+、Node.js 22+
- 已在本地 Docker 中运行的 PostgreSQL（项目不会部署它）。

## 启动

```powershell
# 构建全部模块
mvn clean install -DskipTests

# 平台服务：http://localhost:8080/actuator/health
# 首次启动将在 PostgreSQL 中自动执行 Flyway 迁移，初始化六张核心表
mvn -pl fluxora-platform -am spring-boot:run

# 网关服务：http://localhost:8081/health
mvn -pl fluxora-gateway -am exec:java -Dexec.mainClass=io.fluxora.gateway.FluxoraGatewayApplication

# 前端：http://localhost:5173
cd fluxora-web
npm ci
npm run dev
```

复制 `.env.example` 为 `.env` 后，可覆盖 PostgreSQL、Redis、端口等本地参数。`docker compose up --build` 只启动 platform、gateway、web；基础组件继续使用现有本地 Docker 实例，默认经 `host.docker.internal` 访问。

## 初始账号

首次启动后，平台管理员自动初始化（幂等，不会重置已有密码）：

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `Admin@2026!` | 平台管理员（PLATFORM_ADMIN） |

默认密码仅用于本地开发测试，生产环境请通过环境变量 `INIT_ADMIN_USERNAME`、`INIT_ADMIN_PASSWORD` 覆盖。

## 启动后流程

1. 浏览器打开 `http://localhost:5173/login`
2. 使用 `admin / Admin@2026!` 登录
3. 系统检测到自营租户未初始化，自动跳转初始化向导
4. 填写自营租户名称与管理员信息，点击创建
5. 自营租户创建完成后进入控制台，可管理租户（分页、搜索、筛选、新增、编辑、启停、过期设置、删除）

## API

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | 登录，签发 HttpOnly JWT Cookie | 公开 |
| GET | `/api/auth/me` | 获取当前用户信息 | 登录 |
| POST | `/api/auth/logout` | 退出登录 | 登录 |
| GET | `/api/tenant/self-operated/status` | 自营租户初始化状态 | 登录 |
| POST | `/api/tenant/self-operated/initialize` | 初始化自营租户（幂等） | 登录 |
| GET | `/api/tenant` | 租户分页列表 | 平台管理员 |
| GET | `/api/tenant/{id}` | 租户详情 | 平台管理员 |
| POST | `/api/tenant` | 新增租户 | 平台管理员 |
| PUT | `/api/tenant/{id}` | 编辑租户信息 | 平台管理员 |
| PUT | `/api/tenant/{id}/toggle` | 启停租户 | 平台管理员 |
| DELETE | `/api/tenant/{id}` | 逻辑删除租户 | 平台管理员 |
| GET | `/api/tenant/{tenantId}/members` | 指定租户成员分页 | `MEMBER_READ`（平台管理员路径） |
| GET | `/api/members` | 当前租户成员分页 | `MEMBER_READ`（租户管理员路径） |
| GET | `/api/members/{id}` | 成员详情 | `MEMBER_READ` |
| POST | `/api/tenant/{tenantId}/members` | 创建成员 | `MEMBER_CREATE` |
| PUT | `/api/members/{id}` | 编辑成员基础资料 | `MEMBER_UPDATE` |
| PUT | `/api/members/{id}/role` | 调整成员角色 | `MEMBER_UPDATE` |
| PUT | `/api/members/{id}/enable` | 启用成员 | `MEMBER_ENABLE` |
| PUT | `/api/members/{id}/disable` | 停用成员 | `MEMBER_DISABLE` |
| DELETE | `/api/members/{id}` | 软删除成员 | `MEMBER_DELETE` |
| PUT | `/api/members/{id}/password` | 重置成员密码 | `MEMBER_PASSWORD_RESET` |
| GET | `/api/members/assignable-roles` | 当前操作者可分配的角色 | `MEMBER_READ` |

## 自营租户保护规则

`default` 自营租户（类型 `SELF_OPERATED`）受后端保护：

- **不可删除**：返回"自营租户受保护，无法删除"
- **不可停用**：返回"自营租户受保护，无法停用"
- **租户码不可变更**：创建后不允许修改租户码
- **类型不可变更**：不允许改为 `THIRD_PARTY`

## 成员管理

### 成员、角色、租户归属

- 用户账号 `user_account.scope_type` 区分两类作用域：`PLATFORM` 用户（平台管理员）与 `TENANT` 用户（租户成员）。
- 租户成员必须明确归属于某个具体租户（`tenant_id` 非空）。后端不接受通过前端参数推断租户归属：
  - 平台管理员调用 `/api/tenant/{tenantId}/members*` 时显式指定目标租户；
  - 租户管理员调用 `/api/members*` 时后端从 JWT 强制使用 `currentUser.tenantId`，忽略任何客户端入参，跨租户请求一律返回 403。
- 用户状态分为三态：`ENABLED` / `DISABLED` / `DELETED`。状态由 `enabled` 与 `deleted_at` 派生（详见下文「软删除」），软删除遵循 AGENT.md「软删除字段规范」：`deleted_at TIMESTAMPTZ NULL` 时间戳列，`NULL` 表示未删除，非 `NULL` 表示已删除并记录删除时刻；用户名唯一性使用部分唯一索引（`WHERE deleted_at IS NULL`），软删后用户名可被复用。

### 角色权限差异

| 角色 | 列表/详情 | 创建成员 | 编辑资料 | 调整角色 | 启停 | 删除 | 重置密码 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `PLATFORM_ADMIN`（平台管理员） | ✅ 任意租户 | ✅ 任意租户、可创建 `TENANT_ADMIN` 与 `TENANT_MEMBER` | ✅ | ✅ 可在 `TENANT_ADMIN` ↔ `TENANT_MEMBER` 间切换 | ✅ | ✅ | ✅ |
| `TENANT_ADMIN`（租户管理员） | ✅ 本租户 | ✅ 仅 `TENANT_MEMBER` | ✅ 本租户普通成员 | ✅ 仅可分配 `TENANT_MEMBER` | ✅ 不可操作其他 `TENANT_ADMIN` | ✅ 不可删除其他 `TENANT_ADMIN` | ✅ 不可重置其他 `TENANT_ADMIN` 密码 |
| `TENANT_MEMBER`（普通成员） | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

**禁止项（无论角色，本轮一律不可）**：
- 任何人不可创建 `PLATFORM_ADMIN` 或把成员升级为 `PLATFORM_ADMIN`；
- 租户管理员不可创建/升级/降级任何 `TENANT_ADMIN`。

### 最后一名有效租户管理员保护

为防止误操作导致租户失控，后端服务层在以下三类操作前预检：

- 停用（`PUT /api/members/{id}/disable`）
- 软删除（`DELETE /api/members/{id}`）
- 降级（`PUT /api/members/{id}/role` 由 `TENANT_ADMIN` → 非 `TENANT_ADMIN`）

若目标当前是 `enabled=TRUE` 的 `TENANT_ADMIN`，且执行后该租户内剩余有效 `TENANT_ADMIN` 数量将归零，请求返回 `400 LAST_TENANT_ADMIN_PROTECTED`，前端展示：

> 该租户至少需要保留一名启用状态的租户管理员，无法继续操作

自营租户同样适用该规则；自营租户首位管理员需要先在另一个租户管理员就位后才可被替换。

### 密码规则

- 后端 BCrypt 加密；密码哈希不入响应、不入日志、不入前端状态。
- 强度校验：至少 8 位，含至少 1 个字母与 1 个数字；不符合返回 `PASSWORD_WEAK`。
- 重置密码后旧密码立即失效；前端表单遵循同样的强度规则，提交失败时保留已填写的非敏感字段。

### 浏览器手工验收

按 `## 启动` 节启动 PostgreSQL、Redis、`fluxora-platform`、`fluxora-web` 后，可在浏览器完整走通以下流程，无需手动造数据或调用接口。

1. 以 `admin / Admin@2026!` 登录平台，按向导完成自营租户初始化（已初始化则跳过）。
2. 进入「租户管理」，点击任意租户行尾的 kebab → **管理成员** 进入 `/console/tenants/{id}/members`。
3. 在成员管理页点击 **新增成员**：
   - 角色选择「租户管理员」创建一名 TENANT_ADMIN；
   - 再次新增，角色选择「成员」创建一名 TENANT_MEMBER。
4. 退出登录，使用刚创建的 TENANT_ADMIN 登录：侧边栏出现「成员管理」，进入 `/console/members`，只能看到本租户成员。
5. 在 TENANT_ADMIN 视角下尝试新增成员，角色下拉**仅有「成员」**——不暴露 `PLATFORM_ADMIN`/`TENANT_ADMIN`。
6. 回到平台管理员视角，对普通成员点击「管理成员」→「重置密码」，输入新密码并提交。退出后用**旧密码**登录该成员应失败（提示「用户名或密码错误」），**新密码**应成功。
7. 在租户内只有 1 名 `TENANT_ADMIN` 的情况下，尝试停用 / 删除 / 降级该管理员，应看到「该租户至少需要保留一名启用状态的租户管理员」的中文提示。
8. 软删除某个普通成员后再用相同用户名创建：由于部分唯一索引仅约束未删除记录，新创建应成功。
9. 切换桌面 / 平板 / 移动端视口（1440×900 / 768×1024 / 390×844），确认成员管理页面布局可用，所有失败 toast / dialog 不出现 HTTP 状态码、业务编码、SQL、堆栈或后端原始 message。

## 验证

### 后端
```powershell
# 运行全部后端测试
mvn -pl fluxora-platform test
```

### 前端
```powershell
cd fluxora-web
npm run test -- --run
npm run build
```

### E2E
```powershell
cd fluxora-web
# 需要先启动 platform 和 web
npx playwright test
```

## 错误提示规范

所有面向用户的错误提示遵循 `AGENT.md`：不展示 HTTP 状态码、业务错误编码、Java 异常、SQL 或堆栈。前后端均实现统一安全错误映射。

## 当前阶段边界

### 已实现
- 用户体系：`user_account` 表，PLATFORM / TENANT 双作用域；`deleted_at` 时间戳软删除
- RBAC 权限：`role`、`permission`、`user_role`、`role_permission` 四张表
- 租户体系：`tenant` 表，自营/标准类型，启用/过期/软删除（`deleted_at`，V4 由 `is_deleted` 改造）
- Spring Security + JWT HttpOnly Cookie 认证
- MyBatis XML 全部 SQL
- Flyway 中文注释迁移（V1–V4）
- 平台管理员自动幂等初始化
- 自营租户两步引导初始化
- 租户 CRUD 管理界面
- **成员管理闭环**：分页/详情/创建/编辑/角色调整/启停/软删/重置密码（含跨租户保护、角色升级保护、最后管理员保护、密码强度校验）
- **成员管理前端**：嵌套 `/console/tenants/:tenantId/members`（平台管理员）与 `/console/members`（租户管理员），双视图共用同一组件

### 刻意未实现（本轮范围外）
- Provider、模型、API Key 管理
- 计费、订单、账单
- Redis 缓存、Pub/Sub/Stream、消息队列
- 网关鉴权与 API 中继
- 完整用户/角色/权限后台管理界面
- 短信 / 邮件 / 自助找回密码
- 日志审计
