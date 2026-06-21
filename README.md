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
| `admin` | `admin123` | 平台管理员（PLATFORM_ADMIN） |

默认密码仅用于本地开发测试，生产环境请通过环境变量 `INIT_ADMIN_USERNAME`、`INIT_ADMIN_PASSWORD` 覆盖。

## 启动后流程

1. 浏览器打开 `http://localhost:5173/login`
2. 使用 `admin / admin123` 登录
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

## 自营租户保护规则

`default` 自营租户（类型 `SELF_OPERATED`）受后端保护：

- **不可删除**：返回"自营租户受保护，无法删除"
- **不可停用**：返回"自营租户受保护，无法停用"
- **租户码不可变更**：创建后不允许修改租户码
- **类型不可变更**：不允许改为 `THIRD_PARTY`

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
- 用户体系：`user_account` 表，PLATFORM / TENANT 双作用域
- RBAC 权限：`role`、`permission`、`user_role`、`role_permission` 四张表
- 租户体系：`tenant` 表，自营/第三方类型，启用/过期/软删除
- Spring Security + JWT HttpOnly Cookie 认证
- MyBatis XML 全部 SQL
- Flyway 中文注释迁移
- 平台管理员自动幂等初始化
- 自营租户两步引导初始化
- 租户 CRUD 管理界面

### 刻意未实现（本轮范围外）
- Provider、模型、API Key 管理
- 计费、订单、账单
- Redis 缓存、Pub/Sub/Stream、消息队列
- 网关鉴权与 API 中继
- 完整用户/角色/权限后台管理界面
- 日志审计
